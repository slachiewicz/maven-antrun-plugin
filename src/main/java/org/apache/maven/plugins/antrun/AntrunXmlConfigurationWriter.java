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
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.maven.api.xml.XmlNode;

/**
 * Write the Ant target XML configuration to an XML file.
 */
class AntrunXmlConfigurationWriter {

    private static final String INDENT = "  ";

    private static final Set<String> EXCLUDED_ATTRIBUTES =
            new HashSet<>(Arrays.asList("combine.children", "combine.self"));

    /**
     * @param configuration {@link XmlNode}
     * @param file File to write the configuration to.
     * @param customTaskPrefix Prefix to use for the custom Ant tasks. Empty if no prefix should be used.
     * @param antTargetName Name of the default Ant target.
     * @throws IOException In case of problems.
     */
    public void write(XmlNode configuration, Path file, String customTaskPrefix, String antTargetName)
            throws IOException {
        String eol = System.lineSeparator();
        try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            out.write("<?xml version=\"1.0\" encoding=\"" + AntRunMojo.UTF_8 + "\"?>" + eol);
            out.write("<project name=\"maven-antrun-\" default=\"" + escape(antTargetName) + "\"");
            if (!customTaskPrefix.isEmpty()) {
                out.write(" xmlns:" + customTaskPrefix + "=\"" + AntRunMojo.TASK_URI + "\"");
            }
            out.write(">" + eol);
            write(configuration, out, 1, eol, customTaskPrefix);
            out.write("</project>" + eol);
        }
    }

    private void write(XmlNode node, Writer out, int depth, String eol, String customTaskPrefix) throws IOException {
        String indent = INDENT.repeat(depth);
        String name = qualifiedName(node);
        out.write(indent + "<" + name);
        for (Map.Entry<String, String> ns : node.namespaces().entrySet()) {
            if (!ns.getKey().equals(customTaskPrefix) && !"http://maven.apache.org/ANTRUN".equals(ns.getValue())) {
                out.write(" xmlns:" + ns.getKey() + "=\"" + escape(ns.getValue()) + "\"");
            }
        }
        for (Map.Entry<String, String> attribute : node.attributes().entrySet()) {
            if (!EXCLUDED_ATTRIBUTES.contains(attribute.getKey()) && !isTaskNamespaceDeclaration(attribute)) {
                out.write(" " + attribute.getKey() + "=\"" + escape(attribute.getValue()) + "\"");
            }
        }

        List<XmlNode> children = node.children();
        if (children.isEmpty()) {
            String value = node.value();
            if (value == null) {
                out.write("/>" + eol);
            } else {
                out.write(">" + escape(value) + "</" + name + ">" + eol);
            }
        } else {
            out.write(">" + eol);
            for (XmlNode child : children) {
                write(child, out, depth + 1, eol, customTaskPrefix);
            }
            out.write(indent + "</" + name + ">" + eol);
        }
    }

    /**
     * The namespace of the Maven tasks is declared on the project with the Ant URI, see
     * {@link AntRunMojo#TASK_URI}.
     */
    private static boolean isTaskNamespaceDeclaration(Map.Entry<String, String> attribute) {
        return attribute.getKey().startsWith("xmlns:") && "http://maven.apache.org/ANTRUN".equals(attribute.getValue());
    }

    private static String qualifiedName(XmlNode node) {
        String prefix = node.prefix();
        return prefix == null || prefix.isEmpty() ? node.name() : prefix + ":" + node.name();
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
