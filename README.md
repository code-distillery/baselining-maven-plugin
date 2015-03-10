baselining-maven-plugin
=======================

*NOTE: The maven-bundle-plugin of the Apache Felix project supports baselining as of version 2.5.0. Therfore development on this plugin was discontinued.*

Maven plugin leveraging [bnd's baselining feature][baselining].
The plugin automatically checks an OSGi bundle's package export
versions need to be incremented, in accordance with
[semantic versioning principles][semantic-versioning].

Configuration
-------------

    <plugin>
        <groupId>net.distilledcode.maven</groupId>
        <artifactId>baselining-maven-plugin</artifactId>
        <version>1.0.6</version>
        <executions>
            <execution>
                <id>baseline</id>
                <goals>
                    <goal>baseline</goal>
                </goals>
            </execution>
        </executions>
        <configuration>
            <!-- default:lowerAndUpperBound, options:lowerAndUpperBound,lowerBound,none -->
            <enforcement>none</enforcement>

            <!-- default:false -->
            <skip>false</skip>
        </configuration>
    </plugin>


[baselining]: http://blog.osgi.org/2013/09/baselining-semantic-versioning-made-easy.html
[semantic-versioning]: http://www.osgi.org/wiki/uploads/Links/SemanticVersioning.pdf
