baselining-maven-plugin
=======================

Maven plugin leveraging [bnd's baselining feature][baselining].
The plugin automatically checks an OSGi bundle's package export
versions need to be incremented, in accordance with
[semantic versioning principles][semantic-versioning].

Configuration
-------------

    <plugin>
        <groupId>net.distilledcode.maven</groupId>
        <artifactId>baselining-maven-plugin</artifactId>
        <version>1.0-SNAPSHOT</version>
        <executions>
            <execution>
                <id>baseline</id>
                <goals>
                    <goal>baseline</goal>
                </goals>
            </execution>
        </executions>
        <configuration>
            <!-- failOnError: default=true -->
            <failOnError>false</failOnError>
        </configuration>
    </plugin>


[baselining]: http://blog.osgi.org/2013/09/baselining-semantic-versioning-made-easy.html
[semantic-versioning]: http://www.osgi.org/wiki/uploads/Links/SemanticVersioning.pdf
