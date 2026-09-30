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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.maven.api.xml.XmlNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.xmlunit.builder.DiffBuilder;
import org.xmlunit.builder.Input;
import org.xmlunit.diff.Diff;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Test class for {@link AntrunXmlConfigurationWriter}.
 * @author gboue
 */
class AntrunXmlConfigurationWriterTest {

    private static final String TARGET_NAME = "main";

    @TempDir
    Path folder;

    private AntrunXmlConfigurationWriter configurationWriter;

    private Path file;

    @BeforeEach
    void setUp() throws IOException {
        configurationWriter = new AntrunXmlConfigurationWriter();
        file = Files.createTempFile(folder, "junit", "antrun");
    }

    private static XmlNode target(Map<String, String> attributes, List<XmlNode> children) {
        Map<String, String> all = new LinkedHashMap<>();
        all.put("name", TARGET_NAME);
        all.putAll(attributes);
        return XmlNode.newInstance("target", null, all, children, null);
    }

    private static XmlNode node(String name, String value, Map<String, String> attributes) {
        return XmlNode.newInstance(name, value, attributes, List.of(), null);
    }

    /**
     * Tests that the XML file produced with the writer is pretty printed and that basic attributes are kept.
     *
     * @throws IOException In case of problems
     */
    @Test
    void basic() throws Exception {
        XmlNode configuration = target(Map.of(), List.of(node("echo", null, Map.of("message", "Hello"))));
        configurationWriter.write(configuration, file, "", TARGET_NAME);
        assertXmlIsExpected("/configuration-writer/basic.xml", file);
    }

    /**
     * Tests that serialization is correct even if Ant target is empty (no children, no attributes except name).
     *
     * @throws IOException In case of problems
     */
    @Test
    void emptyTarget() throws Exception {
        configurationWriter.write(target(Map.of(), List.of()), file, "", TARGET_NAME);
        assertXmlIsExpected("/configuration-writer/empty-target.xml", file);
    }

    /**
     * Tests that setting a custom prefix ends up in the project namespace in the target element with the correct name.
     *
     * @throws IOException In case of problems
     */
    @Test
    void customTaskPrefix() throws Exception {
        XmlNode configuration = target(
                Map.of(),
                List.of(
                        node("mvn:foo", "The first value.", Map.of("attr1", "val1")),
                        node("bar", "The second value.", Map.of("attr2", "val2"))));
        configurationWriter.write(configuration, file, "mvn", TARGET_NAME);
        assertXmlIsExpected("/configuration-writer/custom-task-prefix.xml", file);
    }

    /**
     * Tests that combine.children and combine.self attributes in the XML configuration elements are ignored during
     * serialization.
     *
     * @throws IOException In case of problems
     */
    @Test
    void combineAttributes() throws Exception {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("combine.children", "append");
        attributes.put("description", "foo");
        XmlNode configuration = target(attributes, List.of(node("child", null, Map.of("combine.self", "override"))));
        configurationWriter.write(configuration, file, "", TARGET_NAME);
        assertXmlIsExpected("/configuration-writer/combine-attributes.xml", file);
    }

    private void assertXmlIsExpected(String expected, Path file) {
        Diff diff = DiffBuilder.compare(Input.from(getClass().getResourceAsStream(expected)))
                .withTest(Input.from(file.toFile()))
                .checkForIdentical()
                .build();
        assertFalse(diff.hasDifferences(), diff.toString());
    }
}
