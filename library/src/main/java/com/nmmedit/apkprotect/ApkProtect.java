package com.nmmedit.apkprotect;

import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedClassDef;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.DexFile;
import com.android.tools.smali.dexlib2.writer.io.FileDataStore;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import com.mcal.apkparser.xml.ManifestParser;
import com.mcal.apkparser.zip.ZipEntry;
import com.mcal.apkparser.zip.ZipFile;
import com.mcal.apkparser.zip.ZipOutputStream;
import com.nmmedit.apkprotect.data.Prefs;
import com.nmmedit.apkprotect.dex2c.Dex2c;
import com.nmmedit.apkprotect.dex2c.DexConfig;
import com.nmmedit.apkprotect.dex2c.GlobalDexConfig;
import com.nmmedit.apkprotect.dex2c.converter.ClassAnalyzer;
import com.nmmedit.apkprotect.dex2c.converter.instructionrewriter.InstructionRewriter;
import com.nmmedit.apkprotect.dex2c.converter.structs.RegisterNativesUtilClassDef;
import com.nmmedit.apkprotect.dex2c.filters.ClassAndMethodFilter;
import com.nmmedit.apkprotect.log.ApkLogger;
import com.nmmedit.apkprotect.util.ApkUtils;
import com.nmmedit.apkprotect.util.CmakeUtils;
import com.nmmedit.apkprotect.util.FileHelper;
import com.nmmedit.apkprotect.util.ZipHelper;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ApkProtect {

    public static final String ANDROID_MANIFEST_XML = "AndroidManifest.xml";
    public static final String ANDROID_APP_APPLICATION = "android.app.Application";
    @Nullable
    public static ApkLogger apkLogger;
    private final ApkFolders apkFolders;
    private final InstructionRewriter instructionRewriter;
    private final ClassAndMethodFilter filter;
    private final ClassAnalyzer classAnalyzer;

    private ApkProtect(ApkFolders apkFolders,
                       InstructionRewriter instructionRewriter,
                       ClassAndMethodFilter filter,
                       ClassAnalyzer classAnalyzer
    ) {
        this.apkFolders = apkFolders;

        this.instructionRewriter = instructionRewriter;

        this.filter = filter;
        this.classAnalyzer = classAnalyzer;

    }

    //根据apk里文件得到abi，如果没有本地库则返回所有
    private static @NotNull List<String> getAbis(File apk) throws IOException {
        final Pattern pattern = Pattern.compile("lib/(.*)/.*\\.so");
        Set<String> abis = new HashSet<>();
        try (ZipFile zipFile = new ZipFile(apk)) {
            final Enumeration<? extends ZipEntry> entries = zipFile.getEntries();
            while (entries.hasMoreElements()) {
                final ZipEntry entry = entries.nextElement();
                final Matcher matcher = pattern.matcher(entry.getName());
                if (matcher.matches()) {
                    abis.add(matcher.group(1));
                }
            }
            //不支持armeabi，可能还要删除mips相关
            abis.remove("armeabi");
            abis.remove("mips");
            abis.remove("mips64");
            if (abis.isEmpty()) {
                //默认只生成armeabi-v7a
                ArrayList<String> abi = new ArrayList<>();
                if (Prefs.isArm()) {
                    abi.add("armeabi-v7a");
                }
                if (Prefs.isArm64()) {
                    abi.add("arm64-v8a");
                }

                if (Prefs.isX86()) {
                    abi.add("x86");
                }

                if (Prefs.isX64()) {
                    abi.add("x86_64");
                }
                return abi;
            }
        }
        return new ArrayList<>(abis);
    }

    private static @NotNull List<File> getClassesFiles(File apkFile, File zipExtractDir) throws IOException {
        List<File> files = ApkUtils.extractFiles(apkFile, "classes(\\d+)*\\.dex", zipExtractDir);
        //根据classes索引大小排序
        files.sort((file, t1) -> {
            final String numb = file.getName().replace("classes", "").replace(".dex", "");
            final String numb2 = t1.getName().replace("classes", "").replace(".dex", "");
            int n, n2;
            if (numb.isEmpty()) {
                n = 0;
            } else {
                n = Integer.parseInt(numb);
            }
            if (numb2.isEmpty()) {
                n2 = 0;
            } else {
                n2 = Integer.parseInt(numb2);
            }
            return n - n2;
        });
        return files;
    }

    private static @NotNull File dexWriteToFile(DexPool dexPool, int index, @NotNull File dexOutDir) throws IOException {
        if (!dexOutDir.exists()) dexOutDir.mkdirs();

        File outDexFile;
        if (index == 0) {
            outDexFile = new File(dexOutDir, "classes.dex");
        } else {
            outDexFile = new File(dexOutDir, String.format("classes%d.dex", index + 1));
        }
        dexPool.writeTo(new FileDataStore(outDexFile));

        return outDexFile;
    }

    private static List<String> getApplicationClassesFromMainDex(@NotNull GlobalDexConfig globalConfig, String applicationClass) throws IOException {
        final List<String> mainDexClassList = new ArrayList<>();
        String tmpType = classDotNameToType(applicationClass);
        mainDexClassList.add(tmpType);
        for (DexConfig config : globalConfig.getConfigs()) {
            DexBackedDexFile dexFile = DexBackedDexFile.fromInputStream(
                    Opcodes.getDefault(),
                    new BufferedInputStream(new FileInputStream(config.getShellDexFile())));
            final Set<? extends DexBackedClassDef> classes = dexFile.getClasses();
            ClassDef classDef;
            while (true) {
                classDef = getClassDefFromType(classes, tmpType);
                if (classDef == null) {
                    break;
                }
                if (classDotNameToType(ANDROID_APP_APPLICATION).equals(classDef.getSuperclass())) {
                    return mainDexClassList;
                }
                tmpType = classDef.getSuperclass();
                mainDexClassList.add(tmpType);
            }
        }
        return mainDexClassList;
    }

    private static @Nullable ClassDef getClassDefFromType(@NotNull Set<? extends ClassDef> classDefSet, String type) {
        for (ClassDef classDef : classDefSet) {
            if (classDef.getType().equals(type)) {
                return classDef;
            }
        }
        return null;
    }

    /**
     * 给处理过的class注入静态初始化方法,同时dex适当拆分防止dex索引异常
     * 不缓存写好的dexpool,每次切换dexpool时马上把失效的dexpool写入文件,减小内存占用
     *
     * @param globalConfig
     * @param mainClassSet
     * @param maxPoolSize
     * @param dexOutDir
     * @return
     * @throws IOException
     */
    public static @NotNull ArrayList<File> injectInstructionAndWriteToFile(@NotNull GlobalDexConfig globalConfig,
                                                                           Set<String> mainClassSet,
                                                                           int maxPoolSize,
                                                                           File dexOutDir
    ) throws IOException {

        final ArrayList<File> dexFiles = new ArrayList<>();

        DexPool lastDexPool = new DexPool(Opcodes.getDefault());

        final List<DexConfig> configs = globalConfig.getConfigs();
        //第一个dex为main dex
        //提前处理主dex里的类
        for (DexConfig config : configs) {

            DexBackedDexFile dexNativeFile = DexBackedDexFile.fromInputStream(
                    Opcodes.getDefault(),
                    new BufferedInputStream(new FileInputStream(config.getShellDexFile())));

            for (ClassDef classDef : dexNativeFile.getClasses()) {
                if (mainClassSet.contains(classDef.getType())) {
                    //可能保留的类太多,导超出致dex引用,又没再接收返回的dex而导致丢失class
                    Dex2c.injectCallRegisterNativeInsns(config, lastDexPool, mainClassSet, maxPoolSize);
                }
            }
        }

        for (int i = 0; i < configs.size(); i++) {
            DexConfig config = configs.get(i);
            final List<DexPool> retPools = Dex2c.injectCallRegisterNativeInsns(config, lastDexPool, mainClassSet, maxPoolSize);
            if (retPools.isEmpty()) {
                final ApkLogger logger = apkLogger;
                if (logger != null) {
                    logger.error("Dex inject instruction error");
                } else {
                    throw new RuntimeException("Dex inject instruction error");
                }
            }
            if (retPools.size() > 1) {
                for (int k = 0; k < retPools.size() - 1; k++) {
                    final int size = dexFiles.size();
                    final File file = dexWriteToFile(retPools.get(k), size, dexOutDir);
                    dexFiles.add(file);
                }

                lastDexPool = retPools.get(retPools.size() - 1);
                if (i == configs.size() - 1) {
                    final int size = dexFiles.size();
                    final File file = dexWriteToFile(lastDexPool, size, dexOutDir);
                    dexFiles.add(file);
                }
            } else {
                final int size = dexFiles.size();
                final File file = dexWriteToFile(retPools.get(0), size, dexOutDir);
                dexFiles.add(file);


                lastDexPool = new DexPool(Opcodes.getDefault());
            }
        }

        return dexFiles;
    }

    /**
     * 复制dex里所有类到新的dex里
     *
     * @param oldDexFile 原dex
     * @param newDex     目标dex
     */
    public static void copyDex(@NotNull DexFile oldDexFile,
                               @NotNull DexPool newDex) {
        for (ClassDef classDef : oldDexFile.getClasses()) {
            newDex.internClass(classDef);
        }
    }

    //在主dex里增加NativeUtil类
    //返回处理后的dex文件
    public static @NotNull File internNativeUtilClassDef(@NotNull File mainDex,
                                                         @NotNull GlobalDexConfig globalConfig,
                                                         @NotNull String libName) throws IOException {


        DexFile mainDexFile = DexBackedDexFile.fromInputStream(
                Opcodes.getDefault(),
                new BufferedInputStream(new FileInputStream(mainDex)));

        DexPool newDex = new DexPool(Opcodes.getDefault());


        copyDex(mainDexFile, newDex);

        final ArrayList<String> nativeMethodNames = new ArrayList<>();
        for (DexConfig config : globalConfig.getConfigs()) {
            nativeMethodNames.add(config.getRegisterNativesMethodName());
        }


        newDex.internClass(
                new RegisterNativesUtilClassDef("L" + globalConfig.getConfigs().get(0).getRegisterNativesClassName() + ";",
                        nativeMethodNames, libName));

        final File injectLoadLib = new File(mainDex.getParent(), "injectLoadLib");
        if (!injectLoadLib.exists()) injectLoadLib.mkdirs();

        final File newFile = new File(injectLoadLib, mainDex.getName());
        newDex.writeTo(new FileDataStore(newFile));

        return newFile;
    }

    @Contract(pure = true)
    private static @NotNull String classDotNameToType(@NotNull String classDotName) {
        return "L" + classDotName.replace('.', '/') + ";";
    }

    public void run() throws IOException {
        final File apkFile = apkFolders.getInApk();
        final File zipExtractDir = apkFolders.getZipExtractTempDir();

        try {
            final ApkLogger logger = apkLogger;
            byte[] manifestBytes = ZipHelper.getZipFileContent(apkFile, ANDROID_MANIFEST_XML);
            if (manifestBytes == null) {
                if (logger != null) {
                    logger.warning("Not is apk");
                } else {
                    throw new RuntimeException("Not is apk");
                }
            }

            //生成一些需要改变的c代码(随机opcode后的头文件及apk验证代码等)
            CmakeUtils.generateCSources(apkFolders.getDex2cSrcDir(), instructionRewriter);

            //解压得到所有classesN.dex
            List<File> files = getClassesFiles(apkFile, zipExtractDir);
            if (files.isEmpty()) {
                if (logger != null) {
                    logger.warning("No classes.dex");
                } else {
                    throw new RuntimeException("No classes.dex");
                }
            }
            final ManifestParser parser = new ManifestParser(manifestBytes);
            final String minSdkVersion = parser.getMinSdkVersion();
            int minSdk = 21;
            if (minSdkVersion != null && !minSdkVersion.isEmpty()) {
                minSdk = Integer.parseInt(minSdkVersion);
            }
            classAnalyzer.setMinSdk(minSdk);

            if (minSdk < 23) {
                //todo 加载android5的sdk,以保证能正确分析一些有问题的代码
            }

            //


            //先加载apk包含的所有dex文件,以便分析一些有问题的代码
            for (File file : files) {
                classAnalyzer.loadDexFile(file);
            }


            //globalConfig里面configs顺序和classesN.dex文件列表一样
            final GlobalDexConfig globalConfig = Dex2c.handleAllDex(files,
                    filter,
                    instructionRewriter,
                    classAnalyzer,
                    apkFolders.getCodeGeneratedDir());


            //需要放在主dex里的类
            final Set<String> mainDexClassTypeSet = new HashSet<>();
            //todo 可能需要通过外部配置来保留主dex需要的class


            //在处理过的class的静态初始化方法里插入调用注册本地方法的指令
            //static {
            //    NativeUtils.initClass(0);
            //}

            final ArrayList<File> outDexFiles = injectInstructionAndWriteToFile(
                    globalConfig,
                    mainDexClassTypeSet,
                    60000,
                    apkFolders.getTempDexDir());


            final List<String> abis = getAbis(apkFile);

            final Map<String, Map<File, File>> nativeLibs = BuildNativeLib.generateNativeLibs(apkFolders.getOutRootDir(), abis);

            File mainDex = outDexFiles.get(0);

            final File newManDex = internNativeUtilClassDef(
                    mainDex,
                    globalConfig,
                    Prefs.getNmmpName());
            //替换为新的dex
            outDexFiles.set(0, newManDex);

            final File outputApk = apkFolders.getOutputApk();
            if (outputApk.exists()) {
                outputApk.delete();
            }

            try (ZipFile zipFile = new ZipFile(apkFile)) {
                try (ZipOutputStream zos = new ZipOutputStream(outputApk)) {
                    //add AndroidManifest.xml
                    zos.putNextEntry(ANDROID_MANIFEST_XML);
                    zos.write(manifestBytes);
                    zos.closeEntry();

                    //add classesX.dex
                    for (File file : outDexFiles) {
                        zos.putNextEntry(file.getName());
                        zos.write(FileHelper.readBytes(file));
                        zos.closeEntry();
                    }

                    //add native libs
                    for (Map.Entry<String, Map<File, File>> entry : nativeLibs.entrySet()) {
                        final String abi = entry.getKey();
                        for (File file : entry.getValue().values()) {
                            zos.putNextEntry("lib/" + abi + "/" + file.getName());
                            zos.write(FileHelper.readBytes(file));
                            zos.closeEntry();
                        }
                    }

                    final Pattern regex = Pattern.compile(
                            "classes(\\d)*\\.dex" +
//                                    "|META-INF/.*\\.(RSA|DSA|EC|SF|MF)" +
                                    "|AndroidManifest\\.xml");
                    final Enumeration<ZipEntry> enumeration = zipFile.getEntries();
                    while (enumeration.hasMoreElements()) {
                        final ZipEntry ze = enumeration.nextElement();
                        final String entryName = ze.getName();
                        if (regex.matcher(entryName).matches()) {
                            continue;
                        }
                        zos.copyZipEntry(ze, zipFile);
                    }
                }
            }
        } finally {
            //删除解压缓存目录
            FileHelper.deleteFile(zipExtractDir);
        }
    }

    public static class Builder {
        private final ApkFolders apkFolders;
        private InstructionRewriter instructionRewriter;
        private ClassAndMethodFilter filter;
        private ClassAnalyzer classAnalyzer;


        public Builder(ApkFolders apkFolders) {
            this.apkFolders = apkFolders;
        }

        public Builder setInstructionRewriter(InstructionRewriter instructionRewriter) {
            this.instructionRewriter = instructionRewriter;
            return this;
        }


        public Builder setFilter(ClassAndMethodFilter filter) {
            this.filter = filter;
            return this;
        }

        public Builder setClassAnalyzer(ClassAnalyzer classAnalyzer) {
            this.classAnalyzer = classAnalyzer;
            return this;
        }

        public ApkProtect build() {
            final ApkLogger logger = apkLogger;
            if (instructionRewriter == null) {
                if (logger != null) {
                    logger.warning("instructionRewriter == null");
                } else {
                    throw new RuntimeException("instructionRewriter == null");
                }
            }
            if (classAnalyzer == null) {
                if (logger != null) {
                    logger.warning("classAnalyzer == null");
                } else {
                    throw new RuntimeException("classAnalyzer == null");
                }
            }
            return new ApkProtect(apkFolders, instructionRewriter, filter, classAnalyzer);
        }

        public void setLogger(@NotNull ApkLogger logger) {
            apkLogger = logger;
        }
    }
}
