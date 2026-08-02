# vavi.sound.ctrmml

MML Compiler

`vavi.sound.ctrmml` is a java port of [ctrmml](https://github.com/superctr/ctrmml), it compiles
MML into MDSDRV sequence data. The output is byte identical to the original `mmlc`/`mdslink`.

```java
// single song -> .mds container (this is what MdsDriver plays)
Riff mds = MdsLink.compile("my.mml", inputStream, FileResolver.FILE_SYSTEM);

// several songs -> mdsseq.bin / mdspcm.bin
MdsLink.Result result = MdsLink.link(List.of("my.mml", "se.mml"), FileResolver.FILE_SYSTEM);
```

`vavi.sound.ctrmml.compiler.Compiler` implements `musicDriverInterface.ICompiler` and is
registered as a service, so it can also be obtained with
`ICompiler.factory("vavi.sound.ctrmml.compiler.Compiler")`.

The `mdslink` command line is available as `vavi.sound.ctrmml.MdsLink#main`.

Note that ctrmml's VGM export path (`vgm.cpp`, `driver.cpp`, `platform/md.cpp`) and the
`mmlc` optimizer are not ported; this project plays MDS data with its own driver instead.
