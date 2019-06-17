package com.mulesoft.connectors.grpc.mojo;

/*
 * Copyright 2001-2005 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import static org.apache.maven.plugins.annotations.LifecyclePhase.GENERATE_SOURCES;
import static org.apache.maven.plugins.annotations.ResolutionScope.RUNTIME;

import com.mulesoft.connectors.grpc.Generator;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/**
 * Goal which touches a timestamp file.
 *
 * @goal touch
 * @phase process-sources
 */
@Mojo(name = "generate", defaultPhase = GENERATE_SOURCES, requiresDependencyResolution = RUNTIME, threadSafe = true)
public class GPRCConnectMojo extends AbstractMojo {

    @Parameter(property = "project.build.outputDirectory", required = true)
    private File outputDirectory;

    @Parameter(property = "project.build.directory", required = true)
    private File targetFolder;

    @Parameter(property = "project.basedir", required = true)
    private File projectDirectory;

    @Parameter(defaultValue = "${session}", required = true, readonly = true)
    private MavenSession session;

    public void execute() throws MojoExecutionException {

        String packaging = session.getCurrentProject().getPackaging();
        if(packaging.toLowerCase().equals("pom")) {
            return;
        }

        InputStream descriptorStream;
        try {
            descriptorStream = new FileInputStream(new File(outputDirectory, "descriptor.desc"));
            Generator.process(new File(targetFolder, "generated-sources/protobuf/java"), descriptorStream);

        } catch (IOException e) {
            throw new MojoExecutionException(e.getMessage());
        }


    }
}
