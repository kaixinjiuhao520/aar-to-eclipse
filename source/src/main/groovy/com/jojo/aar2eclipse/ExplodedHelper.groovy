package com.jojo.aar2eclipse

import groovy.io.FileType
import org.gradle.api.Project

/**
 * process jars and classes
 * Created by Vigi on 2017/1/20.
 * Modified by kezong on 2018/12/18
 */
class ExplodedHelper {

    static void processLibsIntoLibs(Project project,
                                    Collection<AndroidArchiveLibrary> androidLibraries, Collection<File> jarFiles,
                                    File folderOut) {
        for (androidLibrary in androidLibraries) {
            if (!androidLibrary.rootFolder.exists()) {
                Utils.logInfo('[warning]' + androidLibrary.rootFolder + ' not found!')
                continue
            }
            if (androidLibrary.localJars.isEmpty()) {
                Utils.logInfo("Not found jar file, Library:${androidLibrary.name}")
            } else {
                Utils.logInfo("Merge ${androidLibrary.name} jar file, Library:${androidLibrary.name}")
            }
            androidLibrary.localJars.each {
                Utils.logInfo(it.path)
            }
            project.copy {
                from(androidLibrary.localJars)
                into folderOut
            }
        }
        for (jarFile in jarFiles) {
            if (!jarFile.exists()) {
                Utils.logInfo('[warning]' + jarFile + ' not found!')
                continue
            }
            Utils.logInfo('copy jar from: ' + jarFile + " to " + folderOut.absolutePath)
            project.copy {
                from(jarFile)
                into folderOut
            }
        }
    }

    static void processClassesJarInfoClasses(Project project,
                                             Collection<AndroidArchiveLibrary> androidLibraries,
                                             File folderOut) {
        Utils.logInfo('Merge ClassesJar')
        Collection<File> allJarFiles = new ArrayList<>()
        for (androidLibrary in androidLibraries) {
            if (!androidLibrary.rootFolder.exists()) {
                Utils.logInfo('[warning]' + androidLibrary.rootFolder + ' not found!')
                continue
            }
            allJarFiles.add(androidLibrary.classesJarFile)
        }
        for (jarFile in allJarFiles) {
            project.copy {
                from project.zipTree(jarFile)
                into folderOut
                exclude 'META-INF/'
            }
        }
        //去除AAR里生成的BuildConfig.class
        folderOut.eachFileRecurse(FileType.FILES) { file ->
            if(file.name.endsWith('BuildConfig.class') && file.absolutePath.contains("fataar")) {
                if(file.delete()){
                    Utils.logInfo("delete file: " + file.absolutePath)
                }
            }
        }
    }

    static void processLibsIntoClasses(Project project,
                                   Collection<AndroidArchiveLibrary> androidLibraries, Collection<File> jarFiles,
                                   File folderOut) {
        Utils.logInfo('Merge Libs')
        Collection<File> allJarFiles = new ArrayList<>()
        for (androidLibrary in androidLibraries) {
            if (!androidLibrary.rootFolder.exists()) {
                Utils.logInfo('[warning]' + androidLibrary.rootFolder + ' not found!')
                continue
            }
            Utils.logInfo('[androidLibrary]' + androidLibrary.getName())
            allJarFiles.addAll(androidLibrary.localJars)
        }
        for (jarFile in jarFiles) {
            if (!jarFile.exists()) {
                continue
            }
            allJarFiles.add(jarFile)
        }
        for (jarFile in allJarFiles) {
            project.copy {
                from project.zipTree(jarFile)
                into folderOut
                exclude 'META-INF/'
            }
        }
    }
}
