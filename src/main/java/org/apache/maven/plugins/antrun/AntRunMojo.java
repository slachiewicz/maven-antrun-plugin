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

import java.io.File;
import java.io.IOException;
import java.io.LineNumberReader;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.maven.api.Artifact;
import org.apache.maven.api.Dependency;
import org.apache.maven.api.MojoExecution;
import org.apache.maven.api.Node;
import org.apache.maven.api.PathScope;
import org.apache.maven.api.ProjectScope;
import org.apache.maven.api.Session;
import org.apache.maven.api.di.Inject;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;
import org.apache.maven.api.services.ProjectManager;
import org.apache.maven.api.xml.XmlNode;
import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.DefaultLogger;
import org.apache.tools.ant.Project;
import org.apache.tools.ant.ProjectHelper;
import org.apache.tools.ant.taskdefs.Typedef;
import org.apache.tools.ant.types.Path;

/**
 * <p>
 * Maven AntRun Mojo.
 * <p>
 * This plugin provides the capability of calling Ant tasks from a POM by running the nested Ant tasks inside the
 * &lt;target/&gt; parameter. It is encouraged to move the actual tasks to a separate build.xml file and call that file
 * with an &lt;ant/&gt; task.
 *
 * @author <a href="mailto:kenney@apache.org">Kenney Westerhof</a>
 * @author <a href="mailto:vincent.siveton@gmail.com">Vincent Siveton</a>
 */
@Mojo(name = "run")
public class AntRunMojo implements org.apache.maven.api.plugin.Mojo {

    /**
     * The prefix of all refid used by the plugin.
     */
    public static final String MAVEN_REFID_PREFIX = "maven.";

    /**
     * The refid used to store the Maven project object ({@link org.apache.maven.api.Project}) in the Ant build. If
     * this reference is retrieved in a custom task, note that this will be a clone of the Maven project, and not the
     * project itself, when the task is called through an <code>ant</code> task.
     */
    public static final String DEFAULT_MAVEN_PROJECT_REFID = MAVEN_REFID_PREFIX + "project";

    /**
     * The refid used to store an object of type {@link MavenAntRunProject} containing the Maven project object in the
     * Ant build. This is useful when a custom task needs to change the Maven project, because, unlike
     * {@link #DEFAULT_MAVEN_PROJECT_REFID}, this makes sure to reference the same instance of the Maven project in all
     * cases.
     */
    public static final String DEFAULT_MAVEN_PROJECT_REF_REFID = MAVEN_REFID_PREFIX + "project.ref";

    /**
     * The refid used to store the Maven {@link Session} in the Ant build. It replaces the former
     * <code>maven.project.helper</code> and <code>maven.local.repository.manager</code> references.
     */
    public static final String DEFAULT_MAVEN_SESSION_REFID = MAVEN_REFID_PREFIX + "session";

    /**
     * The refid used to store the list of resolved project dependencies ({@link MavenAntRunDependency}) in the Ant
     * build. It replaces the artifacts of the former <code>maven.project</code> reference.
     */
    public static final String DEFAULT_MAVEN_DEPENDENCIES_REFID = MAVEN_REFID_PREFIX + "dependency.artifacts";

    /**
     * The default target name.
     */
    public static final String DEFAULT_ANT_TARGET_NAME = "main";

    /**
     * The default encoding to use for the generated Ant build.
     */
    public static final String UTF_8 = "UTF-8";

    /**
     * The path to The XML file containing the definition of the Maven tasks.
     */
    public static final String ANTLIB = "org/apache/maven/ant/tasks/antlib.xml";

    /**
     * The URI which defines the built in Ant tasks
     */
    public static final String TASK_URI = "antlib:org.apache.maven.ant.tasks";

    /**
     * The Maven project object
     */
    @Inject
    private org.apache.maven.api.Project mavenProject;

    /**
     * The Maven session object
     */
    @Inject
    private Session session;

    /**
     * The current mojo execution, used to locate the plugin dependencies.
     */
    @Inject
    private MojoExecution mojoExecution;

    @Inject
    private Log log;

    /**
     * The local Maven repository
     *
     * @since 3.2.1
     */
    protected org.apache.maven.api.LocalRepository getLocalRepository() {
        return session.getLocalRepository();
    }

    /**
     * String to prepend to project and dependency property names.
     *
     * @since 1.4
     */
    @Parameter(defaultValue = "")
    private String propertyPrefix;

    /**
     * Maven will look in the target-tag for the namespace of <code>http://maven.apache.org/ANTRUN</code>
     * or <code>antlib:org.apache.maven.ant.tasks</code>
     *
     * <pre>
     *   &lt;configuration&gt;
     *     &lt;target xmlns:mvn="http://maven.apache.org/ANTRUN"&gt;
     *       &lt;mvn:attachartifact/&gt;
     *       &lt;mvn:dependencyfilesets/&gt;
     *     &lt;/target&gt;
     *   &lt;/configuration&gt;
     * </pre>
     *
     * @deprecated only here for backwards compatibility
     * @since 1.5
     */
    @Deprecated
    @Parameter
    private String customTaskPrefix;

    /**
     * The name of a property containing the list of all dependency versions. This is used for the removing the versions
     * from the filenames.
     */
    @Parameter(defaultValue = "maven.project.dependencies.versions")
    private String versionsPropertyName;

    /**
     * The XML for the Ant target. You can add anything you can add between &lt;target&gt; and &lt;/target&gt; in a
     * build.xml.
     *
     * @since 1.5
     */
    @Parameter
    private XmlNode target;

    /**
     * This folder is added to the list of those folders containing source to be compiled. Use this if your Ant script
     * generates source code.
     *
     * @deprecated Use the <code>build-helper-maven-plugin</code> to bind source directories. In version 3.0.0, this
     *             parameter is only defined to break the build if you use it!
     */
    @SuppressWarnings("DeprecatedIsStillUsed")
    @Deprecated
    @Parameter(property = "sourceRoot")
    private String sourceRoot;

    /**
     * This folder is added to the list of those folders containing source to be compiled for testing. Use this if your
     * Ant script generates test source code.
     *
     * @deprecated Use the <code>build-helper-maven-plugin</code> to bind test source directories. For version 3.0.0,
     *             this parameter is only defined to break the build if you use it!
     */
    @SuppressWarnings("DeprecatedIsStillUsed")
    @Deprecated
    @Parameter(property = "testSourceRoot")
    private String testSourceRoot;

    /**
     * Specifies whether the Antrun execution should be skipped.
     *
     * @since 1.7
     */
    @Parameter(property = "maven.antrun.skip", defaultValue = "false")
    private boolean skip;

    /**
     * Specifies whether the Ant properties should propagate to the Maven properties.
     * This only works when the ant task is inline in pom.xml, not when it's loaded from
     * an external ant build.xml file.
     *
     * @since 1.7
     */
    @Parameter(defaultValue = "false")
    private boolean exportAntProperties;

    /**
     * Specifies whether a failure in the Ant build leads to a failure of the Maven build. If this value is
     * {@code false}, the Maven build will proceed even if the Ant build fails. If it is {@code true}, then the Maven
     * build fails if the Ant build fails.
     *
     * @since 1.7
     */
    @Parameter(defaultValue = "true")
    private boolean failOnError;

    @Override
    public void execute() throws MojoException {
        checkDeprecatedParameterUsage(sourceRoot, "sourceRoot", "the build-helper-maven-plugin");
        checkDeprecatedParameterUsage(testSourceRoot, "testSourceRoot", "the build-helper-maven-plugin");
        if (skip) {
            log.info("Skipping Antrun execution");
            return;
        }

        // The injected "target" parameter loses all XML attributes, which Ant tasks rely on, so the raw configuration
        // of the mojo execution is used instead.
        target = mojoExecution
                .getConfiguration()
                .map(configuration -> configuration.child("target"))
                .orElse(target);
        if (target == null) {
            log.info("No Ant target defined - SKIPPED");
            return;
        }

        if (propertyPrefix == null) {
            propertyPrefix = "";
        }

        String antTargetName = target.attribute("name");
        if (antTargetName == null) {
            antTargetName = DEFAULT_ANT_TARGET_NAME;
        }

        Project antProject = new Project();
        antProject.addBuildListener(getConfiguredBuildLogger());
        try {
            java.nio.file.Path antBuildFile = writeTargetToProjectFile(antTargetName);
            ProjectHelper.configureProject(antProject, antBuildFile.toFile());
            antProject.init();

            antProject.setBaseDir(mavenProject.getBasedir().toFile());

            List<MavenAntRunDependency> dependencies = resolveDependencies();
            addAntProjectReferences(mavenProject, antProject, dependencies);
            initMavenTasks(antProject);

            // The Ant project needs actual properties vs. using expression evaluator when calling an external build
            // file.
            copyProperties(mavenProject, antProject, dependencies);

            log.info("Executing tasks");
            antProject.executeTarget(antTargetName);
            log.info("Executed tasks");

            copyProperties(antProject, mavenProject);
        } catch (BuildException e) {
            StringBuilder sb = new StringBuilder();
            sb.append("An Ant BuildException has occurred: ").append(e.getMessage());
            String fragment = findFragment(e);
            if (fragment != null) {
                sb.append("\n").append(fragment);
            }
            if (!failOnError) {
                log.info(sb.toString(), e);
                return; // do not register roots.
            } else {
                throw new MojoException(sb.toString(), e);
            }
        } catch (MojoException e) {
            throw e;
        } catch (Throwable e) {
            throw new MojoException("Error executing Ant tasks: " + e.getMessage(), e);
        }
    }

    private void checkDeprecatedParameterUsage(Object parameter, String name, String replacement) throws MojoException {
        if (parameter != null) {
            throw new MojoException("You are using '" + name + "' which has been removed"
                    + " from the maven-antrun-plugin. Please use '" + replacement
                    + "' and refer to the >>Major Version Upgrade to version 3.0.0<< " + "on the plugin site.");
        }
    }

    private DefaultLogger getConfiguredBuildLogger() {
        DefaultLogger antLogger = new MavenLogger(log);
        if (log.isDebugEnabled()) {
            antLogger.setMessageOutputLevel(Project.MSG_DEBUG);
        } else if (log.isInfoEnabled()) {
            antLogger.setMessageOutputLevel(Project.MSG_INFO);
        } else if (log.isWarnEnabled()) {
            antLogger.setMessageOutputLevel(Project.MSG_WARN);
        } else if (log.isErrorEnabled()) {
            antLogger.setMessageOutputLevel(Project.MSG_ERR);
        } else {
            antLogger.setMessageOutputLevel(Project.MSG_VERBOSE);
        }
        return antLogger;
    }

    /**
     * Resolves all the dependencies of the project (including the test scope) together with their files.
     */
    private List<MavenAntRunDependency> resolveDependencies() {
        Node root = session.collectDependencies(mavenProject, PathScope.TEST_RUNTIME);
        Map<String, Dependency> dependencies = new LinkedHashMap<>();
        for (Node node : session.flattenDependencies(root, PathScope.TEST_RUNTIME)) {
            Dependency dependency = node.getDependency();
            // skip the root node, which is the project itself
            if (dependency != null) {
                dependencies.putIfAbsent(dependency.key(), dependency);
            }
        }
        List<MavenAntRunDependency> result = new ArrayList<>(dependencies.size());
        for (Dependency dependency : dependencies.values()) {
            result.add(new MavenAntRunDependency(
                    dependency, session.resolveArtifact(dependency).getPath()));
        }
        return result;
    }

    private void addAntProjectReferences(
            org.apache.maven.api.Project mavenProject, Project antProject, List<MavenAntRunDependency> dependencies) {
        /* maven.dependency.classpath it's deprecated as it's equal to maven.compile.classpath */
        Path path = getClasspath(antProject, PathScope.MAIN_COMPILE, ProjectScope.MAIN);
        antProject.addReference(MAVEN_REFID_PREFIX + "dependency.classpath", path);
        antProject.addReference(MAVEN_REFID_PREFIX + "compile.classpath", path);

        antProject.addReference(
                MAVEN_REFID_PREFIX + "runtime.classpath",
                getClasspath(antProject, PathScope.MAIN_RUNTIME, ProjectScope.MAIN));

        antProject.addReference(
                MAVEN_REFID_PREFIX + "test.classpath",
                getClasspath(antProject, PathScope.TEST_RUNTIME, ProjectScope.TEST));

        /* set maven.plugin.classpath with plugin dependencies */
        antProject.addReference(MAVEN_REFID_PREFIX + "plugin.classpath", getPluginPath(antProject));

        antProject.addReference(DEFAULT_MAVEN_PROJECT_REFID, mavenProject);
        antProject.addReference(DEFAULT_MAVEN_PROJECT_REF_REFID, new MavenAntRunProject(mavenProject));
        antProject.addReference(DEFAULT_MAVEN_SESSION_REFID, session);
        antProject.addReference(DEFAULT_MAVEN_DEPENDENCIES_REFID, dependencies);
        antProject.addReference(MAVEN_REFID_PREFIX + "local.repository", getLocalRepository());
    }

    /**
     * Builds the classpath of the given scope: the output directories of the project followed by its dependencies.
     */
    private Path getClasspath(Project antProject, PathScope pathScope, ProjectScope projectScope) {
        List<String> elements = new ArrayList<>();
        if (projectScope == ProjectScope.TEST) {
            elements.add(mavenProject.getOutputDirectory(ProjectScope.TEST).toString());
        }
        elements.add(mavenProject.getOutputDirectory(ProjectScope.MAIN).toString());
        for (java.nio.file.Path dependency : session.resolveDependencies(mavenProject, pathScope)) {
            elements.add(dependency.toString());
        }
        Path path = new Path(antProject);
        path.setPath(String.join(File.pathSeparator, elements));
        return path;
    }

    /**
     * @param antProject {@link Project}
     * @return {@link Path} of the plugin artifact and its dependencies.
     */
    private Path getPluginPath(Project antProject) {
        List<String> list = new ArrayList<>();
        ClassLoader realm = mojoExecution.getPlugin().getClassLoader();
        if (realm instanceof URLClassLoader urlClassLoader) {
            // the plugin realm holds the plugin, its dependencies and the dependencies added by the project
            for (URL url : urlClassLoader.getURLs()) {
                if ("file".equals(url.getProtocol())) {
                    try {
                        list.add(java.nio.file.Path.of(url.toURI()).toString());
                    } catch (URISyntaxException e) {
                        throw new MojoException("Invalid plugin classpath entry " + url, e);
                    }
                }
            }
        } else {
            Collection<Artifact> artifacts = new ArrayList<>();
            artifacts.add(mojoExecution.getPlugin().getArtifact());
            artifacts.addAll(mojoExecution.getPlugin().getDependencies());
            for (Artifact artifact : artifacts) {
                java.nio.file.Path file = session.getArtifactPath(artifact)
                        .orElseGet(() -> session.resolveArtifact(artifact).getPath());
                list.add(file.toString());
            }
        }

        Path p = new Path(antProject);
        p.setPath(String.join(File.pathSeparator, list));
        return p;
    }

    /**
     * Copy properties from the Maven project to the Ant project.
     *
     * @param mavenProject {@link org.apache.maven.api.Project}
     * @param antProject {@link Project}
     * @param dependencies the resolved dependencies of the project
     */
    @SuppressWarnings("deprecation")
    public void copyProperties(
            org.apache.maven.api.Project mavenProject, Project antProject, List<MavenAntRunDependency> dependencies) {
        Map<String, String> mavenProps =
                session.getService(ProjectManager.class).getProperties(mavenProject);
        Map<String, String> userProps = session.getUserProperties();
        Set<String> allPropertyKeys = new LinkedHashSet<>(mavenProps.keySet());
        allPropertyKeys.addAll(userProps.keySet());
        for (String key : allPropertyKeys) {
            String value = userProps.getOrDefault(key, mavenProps.get(key));
            antProject.setProperty(key, value);
        }

        // Set the POM file as the ant.file for the tasks run directly in Maven.
        antProject.setProperty(
                "ant.file", mavenProject.getPomPath().toAbsolutePath().toString());

        // Add some of the common Maven properties
        log.debug("Setting properties with prefix: " + propertyPrefix);
        antProject.setProperty((propertyPrefix + "project.groupId"), mavenProject.getGroupId());
        antProject.setProperty((propertyPrefix + "project.artifactId"), mavenProject.getArtifactId());
        antProject.setProperty(
                (propertyPrefix + "project.name"), mavenProject.getModel().getName());
        if (mavenProject.getModel().getDescription() != null) {
            antProject.setProperty(
                    (propertyPrefix + "project.description"),
                    mavenProject.getModel().getDescription());
        }
        antProject.setProperty((propertyPrefix + "project.version"), mavenProject.getVersion());
        antProject.setProperty(
                (propertyPrefix + "project.packaging"),
                mavenProject.getPackaging().id());
        antProject.setProperty(
                (propertyPrefix + "project.build.directory"),
                mavenProject.getBuild().getDirectory());
        antProject.setProperty(
                (propertyPrefix + "project.build.outputDirectory"),
                mavenProject.getBuild().getOutputDirectory());
        antProject.setProperty(
                (propertyPrefix + "project.build.testOutputDirectory"),
                mavenProject.getBuild().getTestOutputDirectory());
        antProject.setProperty(
                (propertyPrefix + "project.build.sourceDirectory"),
                mavenProject.getBuild().getSourceDirectory());
        antProject.setProperty(
                (propertyPrefix + "project.build.testSourceDirectory"),
                mavenProject.getBuild().getTestSourceDirectory());
        String localRepository = getLocalRepository().getPath().toAbsolutePath().toString();
        antProject.setProperty((propertyPrefix + "localRepository"), localRepository);
        antProject.setProperty((propertyPrefix + "settings.localRepository"), localRepository);

        // Add properties for dependency artifacts
        for (MavenAntRunDependency artifact : dependencies) {
            antProject.setProperty(
                    propertyPrefix + artifact.getDependencyConflictId(),
                    artifact.path().toString());
        }

        // Add a property containing the list of versions for the mapper
        StringBuilder versionsBuffer = new StringBuilder();
        for (MavenAntRunDependency artifact : dependencies) {
            versionsBuffer.append(artifact.dependency().getVersion()).append(File.pathSeparator);
        }
        antProject.setProperty(versionsPropertyName, versionsBuffer.toString());
    }

    /**
     * Copy properties from the Ant project to the Maven project.
     *
     * @param antProject not null
     * @param mavenProject not null
     * @since 1.7
     */
    public void copyProperties(Project antProject, org.apache.maven.api.Project mavenProject) {
        if (!exportAntProperties) {
            return;
        }

        log.debug("Propagating Ant properties to Maven properties");
        ProjectManager projectManager = session.getService(ProjectManager.class);
        Map<String, String> mavenProperties = projectManager.getProperties(mavenProject);

        for (Map.Entry<String, Object> entry : antProject.getProperties().entrySet()) {
            String key = entry.getKey();
            if (mavenProperties.get(key) != null) {
                log.warn("Ant property '" + key + "=" + mavenProperties.get(key)
                        + "' clashes with an existing Maven property, SKIPPING this Ant property propagation.");
                continue;
            }
            // it is safe to call toString directly since the value cannot be null in Hashtable
            projectManager.setProperty(mavenProject, key, entry.getValue().toString());
        }
    }

    /**
     * @param antProject {@link Project}
     */
    public void initMavenTasks(Project antProject) {
        log.debug("Initializing Maven Ant Tasks");
        Typedef typedef = new Typedef();
        typedef.setProject(antProject);
        typedef.setResource(ANTLIB);

        if (getTaskPrefix() != null) {
            typedef.setURI(TASK_URI);
        }
        typedef.execute();
    }

    /**
     * Write the Ant target and surrounding tags to a temporary file
     *
     * @throws IOException problem with write to file
     */
    private java.nio.file.Path writeTargetToProjectFile(String targetName) throws IOException {
        // The fileName should probably use the plugin executionId instead of the targetName
        java.nio.file.Path buildFile =
                java.nio.file.Path.of(mavenProject.getBuild().getDirectory(), "antrun", "build-" + targetName + ".xml");
        Files.createDirectories(buildFile.getParent());

        // XmlNode is immutable: rebuild the target with the resolved name
        Map<String, String> attributes = new LinkedHashMap<>(target.attributes());
        attributes.put("name", targetName);
        XmlNode antTarget = XmlNode.newInstance(
                target.name(), target.value(), attributes, target.children(), target.inputLocation());

        String taskPrefix = getTaskPrefix();
        new AntrunXmlConfigurationWriter()
                .write(antTarget, buildFile, taskPrefix == null ? "" : taskPrefix, targetName);

        return buildFile;
    }

    private String getTaskPrefix() {
        String taskPrefix = this.customTaskPrefix;
        if (taskPrefix == null) {
            for (Map<String, String> declarations : List.of(target.namespaces(), target.attributes())) {
                for (Map.Entry<String, String> entry : declarations.entrySet()) {
                    String name = entry.getKey();
                    if ("http://maven.apache.org/ANTRUN".equals(entry.getValue())) {
                        if (name.startsWith("xmlns:")) {
                            return name.substring("xmlns:".length());
                        } else if (declarations == target.namespaces()) {
                            return name;
                        }
                    }
                }
            }
        }
        return taskPrefix;
    }

    /**
     * @param buildException not null
     * @return the fragment XML part where the buildException occurs.
     * @since 1.7
     */
    private String findFragment(BuildException buildException) {
        if (buildException == null
                || buildException.getLocation() == null
                || buildException.getLocation().getFileName() == null) {
            return null;
        }

        File antFile = new File(buildException.getLocation().getFileName());
        if (!antFile.exists()) {
            return null;
        }

        try (LineNumberReader reader =
                new LineNumberReader(Files.newBufferedReader(antFile.toPath(), StandardCharsets.UTF_8))) {
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                if (reader.getLineNumber() == buildException.getLocation().getLineNumber()) {
                    return "around Ant part ..." + line.trim() + "... @ "
                            + buildException.getLocation().getLineNumber() + ":"
                            + buildException.getLocation().getColumnNumber() + " in " + antFile.getAbsolutePath();
                }
            }
        } catch (Exception e) {
            log.debug(e.getMessage(), e);
        }

        return null;
    }
}
