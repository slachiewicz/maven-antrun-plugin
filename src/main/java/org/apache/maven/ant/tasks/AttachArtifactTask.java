/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.maven.ant.tasks;

import java.io.File;

import org.apache.maven.api.ProducedArtifact;
import org.apache.maven.api.Session;
import org.apache.maven.api.services.ProjectManager;
import org.apache.maven.plugins.antrun.AntRunMojo;
import org.apache.maven.plugins.antrun.MavenAntRunProject;
import org.apache.maven.plugins.antrun.taskconfig.AttachArtifactConfiguration;
import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.Project;
import org.apache.tools.ant.Task;

/**
 *
 */
public class AttachArtifactTask extends Task {

    /**
     * The refId of the Maven project.
     */
    private String mavenProjectRefId = AntRunMojo.DEFAULT_MAVEN_PROJECT_REF_REFID;

    /**
     * The refId of the Maven session.
     */
    @SuppressWarnings("FieldCanBeLocal")
    private String mavenSessionRefId = AntRunMojo.DEFAULT_MAVEN_SESSION_REFID;

    private AttachArtifactConfiguration configuration = new AttachArtifactConfiguration();

    @Override
    public void execute() {
        File file = configuration.getFile();
        if (file == null) {
            throw new BuildException("File is a required parameter.");
        }

        if (!file.exists()) {
            throw new BuildException("File does not exist: " + file);
        }

        if (this.getProject().getReference(mavenProjectRefId) == null) {
            throw new BuildException("Maven project reference not found: " + mavenProjectRefId);
        }

        String type = configuration.getType();
        if (type == null) {
            type = extension(file.getName());
        }

        org.apache.maven.api.Project mavenProject =
                ((MavenAntRunProject) this.getProject().getReference(mavenProjectRefId)).getMavenProject();

        if (this.getProject().getReference(mavenSessionRefId) == null) {
            throw new BuildException("Maven session reference not found: " + mavenSessionRefId);
        }

        String classifier = configuration.getClassifier();
        log("Attaching " + file + " as an attached artifact", Project.MSG_VERBOSE);
        Session session = getProject().getReference(mavenSessionRefId);
        ProducedArtifact artifact = session.createProducedArtifact(
                mavenProject.getGroupId(),
                mavenProject.getArtifactId(),
                mavenProject.getVersion(),
                classifier,
                type,
                type);
        session.getService(ProjectManager.class).attachArtifact(mavenProject, artifact, file.toPath());
    }

    private static String extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1);
    }

    /**
     * @return {@link #mavenProjectRefId}
     */
    public String getMavenProjectRefId() {
        return mavenProjectRefId;
    }

    /**
     * @param mavenProjectRefId {@link #mavenProjectRefId}
     */
    public void setMavenProjectRefId(String mavenProjectRefId) {
        this.mavenProjectRefId = mavenProjectRefId;
    }

    /* Fields delegated to AttachArtifactConfiguration */

    public File getFile() {
        return this.configuration.getFile();
    }

    public void setFile(File file) {
        this.configuration.setFile(file);
    }

    public String getClassifier() {
        return this.configuration.getClassifier();
    }

    public void setClassifier(String classifier) {
        this.configuration.setClassifier(classifier);
    }

    public String getType() {
        return this.configuration.getType();
    }

    public void setType(String type) {
        this.configuration.setType(type);
    }
}
