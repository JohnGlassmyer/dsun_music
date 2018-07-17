Code I wrote to extract music, images, and other data from the video game _Dark Sun: Shattered Lands_.

  * gff-tool extracts or replaces the contents of _Dark Sun_'s resource (.GFF) files.
  * xmi-tool describes and modifies the music sequences (PSEQ, LSEQ, GSEQ).
  * image-tool exports (still and animated) bitmap images as TIFFs.
  * region-tool generates TIFF images displaying the terrain of the game's regions.

Have a JDK and Apache Maven installed, check out the code, and run `mvn package` in that directory. This will produce four JAR files:
  * ./gff-tool/target/gff-tool.jar
  * ./image-tool/target/image-tool.jar
  * ./region-tool/target/region-tool.jar
  * ./xmi-tool/target/xmi-tool.jar

You can run any of these with `java -jar <JAR_FILE> --help` for further instructions.

License is GPLv3.
