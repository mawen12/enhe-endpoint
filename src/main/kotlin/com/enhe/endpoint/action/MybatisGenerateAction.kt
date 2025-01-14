// @author  ding.shichen
// @email   foreverhuiqiao@126.com
// @date    2023-01-05

package com.enhe.endpoint.action

import com.enhe.endpoint.database.EFCodeGenerateService
import com.enhe.endpoint.database.model.EFColumn
import com.enhe.endpoint.database.model.EFTable
import com.enhe.endpoint.database.MysqlColumnType
import com.enhe.endpoint.dialog.MybatisGeneratorDialog
import com.enhe.endpoint.extend.or
import com.enhe.endpoint.notifier.EnheNotifier
import com.intellij.database.psi.DbTable
import com.intellij.database.util.DasUtil
import com.intellij.ide.util.PackageUtil
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.LangDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.jps.model.java.JavaSourceRootType

/**
 * Mybatis 生成工具按钮
 */
class MybatisGenerateAction : AnAction() {

    @Suppress("removal")
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val table = e.getData(LangDataKeys.PSI_ELEMENT) ?: return
        when (table) {
            is DbTable -> {
                if (table.dataSource.databaseVersion.name != "MySQL") {
                    EnheNotifier.error(project, "目前仅支持 MySQL 数据库")
                    return
                }
                val resolveObjects = DasUtil.getPrimaryKey(table)?.columnsRef?.resolveObjects()
                DasUtil.getColumns(table).map {
                    EFColumn(
                        it.name,
                        MysqlColumnType.of(it.dataType.typeName),
                        it.isNotNull,
                        it.comment.orEmpty(),
                        resolveObjects?.any { pk -> pk.name == it.name } ?: false,
                        it.toString().contains("auto_increment"),
                        resolveObjects?.first()?.name == it.name
                    )
                }.toList().run {
                    try {
                        showGeneratorDialog(project, EFTable(table.name, table.comment.orEmpty(), this))
                    } catch (e: Throwable) {
                        thisLogger().error("生成失败", e)
                        EnheNotifier.error(project, e.message.or("生成失败"))
                    }
                }
            }
        }
    }

    private fun showGeneratorDialog(project: Project, table: EFTable) {
        MybatisGeneratorDialog(project, table).apply {
            if (showAndGet()) {
                val persistent = getPersistentState()
                val implTempState = getImplTempState()
                val persistentModule = persistent.persistentModule

                val sourceDir = findSourceDir(project, persistentModule) ?: return
                val entityDir = findOrCreateDir(project, persistentModule, persistent.entityPackageName, sourceDir) ?: return
                val mapperDir = findOrCreateDir(project, persistentModule, persistent.mapperPackageName, sourceDir) ?: return

                runWriteCommand(project, "Generate persistent") {
                    EFCodeGenerateService.getInstance(project).run {
                        executeGenerateEntity(project, entityDir, table, persistent, implTempState)
                        executeGenerateMapper(project, mapperDir, persistent, implTempState)
                        executeGenerateXml(project, mapperDir, table, persistent, implTempState)
                    }
                }

                val javaPsiFacade = JavaPsiFacade.getInstance(project)
                val goToEntity = javaPsiFacade.toViewAction(persistent.entityQualified, persistentModule, "Entity")
                val goToMapper = javaPsiFacade.toViewAction(persistent.mapperQualified, persistentModule, "Mapper")
                val goToXml = FilenameIndex.getVirtualFilesByName(
                    persistent.xmlFileName,
                    GlobalSearchScope.moduleScope(persistentModule)
                ).first().let { vf ->
                    PsiManager.getInstance(project).findFile(vf)?.let { ViewFileNotificationAction("Xml", it) }
                }
                if (goToEntity != null && goToMapper != null && goToXml != null) {
                    EnheNotifier.info(project, "持久层生成成功", goToEntity, goToMapper, goToXml)
                }

                if (isEnableControlService()) {
                    val controlService = getControlServiceState()

                    val controlSourceDir = findSourceDir(project, controlService.controlModule) ?: return
                    val clientSourceDir = findSourceDir(project, controlService.clientModule) ?: return
                    val serviceImplSourceDir = findSourceDir(project, controlService.serviceImplModule) ?: return

                    val controlDir = findOrCreateDir(project, controlService.controlModule, controlService.controlPackageName, controlSourceDir) ?: return
                    val clientDir = findOrCreateDir(project, controlService.clientModule, controlService.clientPackageName, clientSourceDir) ?: return
                    val serviceImplDir = findOrCreateDir(project, controlService.serviceImplModule, controlService.serviceImplPackageName, serviceImplSourceDir) ?: return

                    runWriteCommand(project, "Generate control and service") {
                        EFCodeGenerateService.getInstance(project).run {
                            executeGenerateClient(project, clientSourceDir, clientDir, table, controlService, implTempState)
                            executeGenerateServiceImpl(project, serviceImplDir, controlService, implTempState)
                            executeGenerateController(project, controlDir, table, controlService)
                        }
                    }

                    val goToClient = javaPsiFacade.toViewAction(controlService.clientQualified, controlService.clientModule, "FeignClient")
                    val goToServiceImpl = javaPsiFacade.toViewAction(controlService.serviceImplQualified, controlService.serviceImplModule, "ServiceImpl")
                    val goToController = javaPsiFacade.toViewAction(controlService.controlQualified, controlService.controlModule, "Controller")
                    if (goToClient != null && goToServiceImpl != null && goToController != null ) {
                        EnheNotifier.info(project, "控制层服务层生成成功", goToClient, goToServiceImpl, goToController)
                    }
                }
            }
        }
    }

    /**
     * 获取模块的资源目录
     */
    private fun findSourceDir(project: Project, persistentModule: Module): PsiDirectory? {
        val sourceRoots = ModuleRootManager.getInstance(persistentModule).getSourceRoots(JavaSourceRootType.SOURCE)
        if (sourceRoots.isEmpty()) {
            EnheNotifier.error(project, "获取不到模块内的 Java 资源目录")
            return null
        }
        val psiManager = PsiManager.getInstance(project)
        val sourceDir = psiManager.findDirectory(sourceRoots[0])
        if (sourceDir == null) {
            EnheNotifier.error(project, "获取不到资源目录")
        }
        return sourceDir
    }

    /**
     * 寻找或创建目录
     */
    private fun findOrCreateDir(project: Project, module: Module, packageName: String, baseDir: PsiDirectory): PsiDirectory? {
        val dir = PackageUtil.findOrCreateDirectoryForPackage(module, packageName, baseDir,
            false, true)
        if (dir == null) {
            EnheNotifier.error(project, "$packageName 包目录创建失败")
        }
        return dir
    }

    /**
     * 找到 PsiClass 返回一个查看按钮
     */
    private fun JavaPsiFacade.toViewAction(qualifiedName: String, module: Module, actionText: String): ViewFileNotificationAction? {
        return findClass(qualifiedName, GlobalSearchScope.moduleScope(module))?.let { ViewFileNotificationAction(actionText, it) }
    }

    private fun runWriteCommand(project: Project, command: String, runnable: () -> Unit) {
        ApplicationManager.getApplication().runWriteAction {
            CommandProcessor.getInstance().executeCommand(project, runnable, command, null)
        }
    }

}