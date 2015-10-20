Code I wrote to extract the music from the video game _Dark Sun: Shattered Lands_.

GffTool is for editing the contents of _Dark Sun_'s resource (.GFF) files. XmiTool is for modifying the GSEQ (music) files contained therein.

Have a JDK and Apache Maven installed, check out the code, and run `mvn package` in that directory. This will produce two JAR files:
  * ./gfftool/target/gfftool.jar
  * ./xmitool/target/xmitool.jar

You can run either of these with `java -jar <JAR_FILE> --help` for further instructions.

License is GPLv3.
