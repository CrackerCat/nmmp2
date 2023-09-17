package com.nmmedit.apkprotect.aab;

import org.jetbrains.annotations.NotNull;

import java.io.File;

public class AabFolders {
    @NotNull
    private final File inAab;
    @NotNull
    private final File outRootDir;

    public AabFolders(@NotNull File inAab, @NotNull File outRootDir) {
        this.inAab = inAab;
        this.outRootDir = outRootDir;
    }

    @NotNull
    public File getInAab() {
        return inAab;
    }

    @NotNull
    public File getOutRootDir() {
        return outRootDir;
    }

    //apk解压目录,生成新apk之后可以删除
    @NotNull
    public File getZipExtractTempDir() {
        return new File(outRootDir, ".apk_temp");
    }

    //c源代码输出目录
    @NotNull
    public File getDex2cSrcDir() {
        return new File(outRootDir, "dex2c");
    }

    //c文件及对应dex输出目录
    @NotNull
    public File getCodeGeneratedDir() {
        return new File(getDex2cSrcDir(), "generated");
    }

    //在处理过的classes.dex里插入jni初始化代码及主classes.dex加载so库代码,生成新dex输出目录
    //这个目录可以删除,但是为了更好debug之类就保留了方便查看dex
    @NotNull
    public File getTempDexDir() {
        return new File(outRootDir, "dex_output");
    }


    @NotNull
    public File getOutputAab() {
        String name = inAab.getName();
        final int i = name.lastIndexOf('.');
        if (i != -1) {
            name = name.substring(0, i);
        }
        return new File(outRootDir, name + "-protect.aab");
    }
}
