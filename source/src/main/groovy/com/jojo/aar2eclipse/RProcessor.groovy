package com.jojo.aar2eclipse

import com.android.build.gradle.api.LibraryVariant

import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.bundling.Zip
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.tasks.Jar

import java.text.SimpleDateFormat


/**
 * R file processor
 * @author kezong on 2019/7/16.
 */
class RProcessor {

    private final Project mProject
    private final LibraryVariant mVariant

    private final File mJavaDir
    private final File mClassDir
    private final File mJarDir
    private final File mAarUnZipDir
    private final File mAarOutputDir
    private final String mGradlePluginVersion
    private String mAarOutputPath
    private VersionAdapter mVersionAdapter
    private final Collection<AndroidArchiveLibrary> mLibraries

    RProcessor(Project project, LibraryVariant variant, Collection<AndroidArchiveLibrary> libraries, String version) {
        mProject = project
        mVariant = variant
        mLibraries = libraries
        mGradlePluginVersion = version
        mVersionAdapter = new VersionAdapter(project, variant, version)
        // R.java dir
        mJavaDir = mProject.file("${mProject.getBuildDir()}/intermediates/fat-R/r/${mVariant.dirName}")
        // R.class compile dir
        mClassDir = mProject.file("${mProject.getBuildDir()}/intermediates/fat-R/r-class/${mVariant.dirName}")
        // R.jar dir
        //mJarDir = mProject.file("${mProject.getBuildDir()}/outputs/aar-R/${mVariant.dirName}/libs")
        mJarDir = mProject.file("${mProject.getBuildDir()}/outputs/eclipse/libs")
        // aar zip file
        mAarUnZipDir = mJarDir.getParentFile()
        // aar output dir
        mAarOutputDir = mProject.file("${mProject.getBuildDir()}/outputs/aar/")
        mAarOutputPath = mVariant.outputs.first().outputFile.absolutePath
    }

    void inject(Task bundleTask) {
        def RFileTask = createRFileTask(mJavaDir)
        def RClassTask = createRClassTask(mJavaDir, mClassDir)
        def RJarTask = createRJarTask(mClassDir, mJarDir)
        def reBundleAar = createBundleAarTask(mAarUnZipDir, mAarOutputDir, mAarOutputPath)

        reBundleAar.doFirst {
            mProject.copy {
                from mProject.zipTree(mAarOutputPath)
                into mAarUnZipDir
            }
            deleteEmptyDir(mAarUnZipDir)
            deleteEmptyDir(mProject.file("eclipse"))
        }

        reBundleAar.doLast {
            File output = mProject.file("eclipse")
            File clsJarFile = new File(mAarUnZipDir.absolutePath + File.separator + "classes.jar")
            mProject.copy {
                from clsJarFile
                into mJarDir
                //找到对应的名字
                rename {
                    filename ->
                        filename.replace 'classes.jar', 'mergedClz-'+now()+'.jar'
                }
            }
            clsJarFile.delete()
            File RtxtFile = new File(mAarUnZipDir.absolutePath + File.separator + "R.txt")
            RtxtFile.delete()
            //写入eclipse libraray project的属性文件
            File projectFile = new File(mAarUnZipDir.absolutePath + File.separator + "project.properties")
            FileWriter writer= new FileWriter(projectFile)
            writer.write("target=android-26"+System.lineSeparator())
            writer.write("sdk.buildtools=25.0.2"+System.lineSeparator())
            writer.write("android.library=true"+System.lineSeparator())
            writer.close()

            mProject.copy {
                from mAarUnZipDir
                into output

            }
            Utils.logAnytime("============out dir============: $output")
        }

        bundleTask.doFirst {
            File f = new File(mAarOutputPath)
            if (f.exists()) {
                f.delete()
            }
            mJarDir.getParentFile().deleteDir()
            mJarDir.mkdirs()
        }

        bundleTask.doLast {
            // support gradle 5.1 && gradle plugin 3.4 before, the outputName is changed
            File file = new File(mAarOutputPath)
            if (!file.exists()) {
                mAarOutputPath = mAarOutputDir.absolutePath + "/" + mProject.name + ".aar"
                reBundleAar.archiveName = new File(mAarOutputPath).name
            }
        }

        bundleTask.finalizedBy(RFileTask)
        RFileTask.finalizedBy(RClassTask)
        RClassTask.finalizedBy(RJarTask)
        RJarTask.finalizedBy(reBundleAar)
    }

    private def now(){
        def date = new Date()
        def sdf = new SimpleDateFormat("yyyyMMddHHmmss")
        return sdf.format(date)
    }

    private def createRFile(AndroidArchiveLibrary library, def rFolder, ConfigObject symbolsMap) {
        def libPackageName = mVariant.getApplicationId()
        def aarPackageName = library.getPackageName()

        String packagePath = aarPackageName.replace('.', '/')

        def rTxt = library.getSymbolFile()
        def rMap = new ConfigObject()

        if (rTxt.exists()) {
            rTxt.eachLine { line ->
                def (type, subclass, name, value) = line.tokenize(' ')
                if (symbolsMap.containsKey(subclass) && symbolsMap.get(subclass).getAt(name) == type) {
                    rMap[subclass].putAt(name, type)
                }
            }
        }

        def sb = "package $aarPackageName;" << '\n' << '\n'
        sb << 'public final class R {' << '\n'
        rMap.each { subclass, values ->
            sb << "  public static final class $subclass {" << '\n'
            values.each { name, type ->
                sb << "    public static final $type $name = ${libPackageName}.R.${subclass}.${name};" << '\n'
            }

            sb << "    }" << '\n'
        }

        sb << '}' << '\n'

        new File("${rFolder.path}/$packagePath").mkdirs()
        FileOutputStream outputStream = new FileOutputStream("${rFolder.path}/$packagePath/R.java")
        outputStream.write(sb.toString().getBytes())
        outputStream.close()
    }

    private def getSymbolsMap() {
        def file = mVersionAdapter.getSymbolFile()
        if (!file.exists()) {
            throw IllegalAccessException("{$file.absolutePath} not found")
        }

        def map = new ConfigObject()
        file.eachLine { line ->
            def (type, subclass, name, value) = line.tokenize(' ')
            map[subclass].putAt(name, type)
        }

        return map
    }

    private Task createRFileTask(final File destFolder) {
        def task = mProject.tasks.create(name: 'createRsFile' + mVariant.name)
        task.doLast {
            if (destFolder.exists()) {
                destFolder.deleteDir()
            }
            if (mLibraries != null && mLibraries.size() > 0) {
                def symbolsMap = getSymbolsMap()
                mLibraries.each {
                    Utils.logInfo("Generate R File, Library:${it.name}")
                    createRFile(it, destFolder, symbolsMap)
                }
            }
        }

        return task
    }

    private Task createRClassTask(final def sourceDir, final def destinationDir) {
        mProject.mkdir(destinationDir)

        def classpath = mVersionAdapter.getRClassPath()
        String taskName = "compileRs${mVariant.name.capitalize()}"
        Task task = mProject.getTasks().create(taskName, JavaCompile.class, {
            it.source = sourceDir.path
            it.sourceCompatibility = mProject.android.compileOptions.sourceCompatibility
            it.targetCompatibility = mProject.android.compileOptions.targetCompatibility
            it.classpath = classpath
            it.destinationDir destinationDir
        })

        task.doFirst {
            Utils.logInfo("Compile R.class, Dir:${sourceDir.path}")
            Utils.logInfo("Compile R.class, classpath:${classpath.first().absolutePath}")

            if (mGradlePluginVersion != null && Utils.compareVersion(mGradlePluginVersion, "3.3.0") >= 0) {
                mProject.copy {
                    from mProject.zipTree(mVersionAdapter.getRClassPath().first().absolutePath + "/R.jar")
                    into mVersionAdapter.getRClassPath().first().absolutePath
                }
            }
        }
        return task
    }

    private Task createRJarTask(final def fromDir, final def desFile) {
        String taskName = "createRsJar${mVariant.name.capitalize()}"
        Task task = mProject.getTasks().create(taskName, Jar.class, {
            it.from fromDir.path
            it.archiveName = "r-classes.jar"
            it.destinationDir desFile
        })
        task.doFirst {
            Utils.logInfo("Generate R.jar, Dir：$fromDir")
        }
        return task
    }

    private Task createBundleAarTask(final File from, final File destDir, final String filePath) {
        String taskName = "reBundleAar${mVariant.name.capitalize()}"
        Task task = mProject.getTasks().create(taskName, Zip.class, {
            it.from from
            it.include "**"
            it.archiveName = new File(filePath).name
            it.destinationDir(destDir)
        })

        return task
    }

    def deleteEmptyDir = { file ->
        file.listFiles().each { x ->
            if (x.isDirectory()) {
                if (x.listFiles().size() == 0) {
                    x.delete()
                } else {
                    deleteEmptyDir(x)
                    if (x.listFiles().size() == 0) {
                        x.delete()
                    }
                }
            }
        }
    }
}
