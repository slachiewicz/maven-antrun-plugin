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
package org.apache.maven.plugins.antrun;

import java.nio.file.Path;

import org.apache.maven.api.Dependency;

/**
 * A resolved project dependency, registered as an Ant reference under {@link AntRunMojo#DEFAULT_MAVEN_DEPENDENCIES_REFID}.
 * This replaces the set of {@code org.apache.maven.artifact.Artifact} returned by {@code MavenProject.getArtifacts()}.
 *
 * @param dependency the dependency (coordinates, type and scope)
 * @param path the path of the resolved file
 */
public record MavenAntRunDependency(Dependency dependency, Path path) {

    /**
     * @return the dependency conflict id, {@code groupId:artifactId:type[:classifier]}
     */
    public String getDependencyConflictId() {
        String id = dependency.getGroupId() + ":" + dependency.getArtifactId() + ":"
                + dependency.getType().id();
        String classifier = dependency.getClassifier();
        return classifier == null || classifier.isEmpty() ? id : id + ":" + classifier;
    }
}
