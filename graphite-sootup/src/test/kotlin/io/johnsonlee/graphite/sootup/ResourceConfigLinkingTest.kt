package io.johnsonlee.graphite.sootup

import io.johnsonlee.graphite.core.CallSiteNode
import io.johnsonlee.graphite.core.NodeId
import io.johnsonlee.graphite.core.ResourceEdge
import io.johnsonlee.graphite.core.ResourceFileNode
import io.johnsonlee.graphite.core.ResourceRelation
import io.johnsonlee.graphite.core.ResourceValueNode
import io.johnsonlee.graphite.graph.Graph
import io.johnsonlee.graphite.graph.MethodPattern
import io.johnsonlee.graphite.input.LoaderConfig
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ResourceConfigLinkingTest {

    @Test
    fun `should keep only resource file nodes and file level generic links`() {
        val fixtureDir = createFixtureDir()
        try {
            val loader = JavaProjectLoader(
                LoaderConfig(
                    includePackages = listOf("sample.resources"),
                    buildCallGraph = false
                )
            )

            val graph = loader.load(fixtureDir)
            assertTrue(graph.nodes(ResourceValueNode::class.java).none(), "ResourceValueNode should not be created from file contents")

            val appProperties = requireResourceFile(graph, "application.properties")
            val appXml = requireResourceFile(graph, "application.xml")
            val appJson = requireResourceFile(graph, "application.json")
            val configXml = requireResourceFile(graph, "config.xml")

            val featureMethod = graph.methods(MethodPattern("sample.resources.ResourceConfig", "featureMode")).single()
            val featureXmlMethod = graph.methods(MethodPattern("sample.resources.ResourceConfig", "featureModeXml")).single()
            val featureReaderMethod = graph.methods(MethodPattern("sample.resources.ResourceConfig", "featureModeReader")).single()
            val jsonMethod = graph.methods(MethodPattern("sample.resources.ResourceConfig", "jsonFeatureEnabled")).single()
            val xmlMethod = graph.methods(MethodPattern("sample.resources.ResourceConfig", "xmlServiceEndpoint")).single()

            val openPropertiesCall = requireCallSite(graph, featureMethod, "getResourceAsStream")
            val lookupPropertiesCall = requireCallSite(graph, featureMethod, "getProperty")
            val openXmlCall = requireCallSite(graph, featureXmlMethod, "getResourceAsStream")
            val lookupXmlCall = requireCallSite(graph, featureXmlMethod, "getProperty")
            val lookupReaderCall = requireCallSite(graph, featureReaderMethod, "getProperty")
            val jsonLoadCall = requireCallSite(graph, jsonMethod, "fromJson")
            val xmlLoadCall = requireCallSite(graph, xmlMethod, "parse")

            assertHasResourceEdge(graph.incomingResourceEdges(openPropertiesCall.id), appProperties.id, ResourceRelation.OPENS, "application.properties should open into getResourceAsStream")
            assertHasResourceEdge(graph.incomingResourceEdges(lookupPropertiesCall.id), appProperties.id, ResourceRelation.LOOKUP, "application.properties should link to Properties.getProperty")
            assertHasResourceEdge(graph.incomingResourceEdges(openXmlCall.id), appXml.id, ResourceRelation.OPENS, "application.xml should open into getResourceAsStream")
            assertHasResourceEdge(graph.incomingResourceEdges(lookupXmlCall.id), appXml.id, ResourceRelation.LOOKUP, "application.xml should link to Properties.getProperty")
            assertHasResourceEdge(graph.incomingResourceEdges(lookupReaderCall.id), appProperties.id, ResourceRelation.LOOKUP, "Reader-backed Properties.getProperty should stay linked to application.properties")
            assertHasResourceEdge(graph.incomingResourceEdges(jsonLoadCall.id), appJson.id, ResourceRelation.LOADS, "application.json should link to Gson.fromJson")
            assertHasResourceEdge(graph.incomingResourceEdges(xmlLoadCall.id), configXml.id, ResourceRelation.LOADS, "config.xml should link to DocumentBuilder.parse")
        } finally {
            fixtureDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `should link ResourceBundle and PropertyResourceBundle by file path`() {
        val fixtureDir = createFixtureDir()
        try {
            val loader = JavaProjectLoader(
                LoaderConfig(
                    includePackages = listOf("sample.resources"),
                    buildCallGraph = false
                )
            )

            val graph = loader.load(fixtureDir)
            assertTrue(graph.nodes(ResourceValueNode::class.java).none(), "ResourceValueNode should not be created from bundle contents")

            val bundleRoot = requireResourceFile(graph, "messages.properties")
            val bundleKo = requireResourceFile(graph, "messages_ko.properties")
            val bundleKoKr = requireResourceFile(graph, "messages_ko_KR.properties")
            val listBundle = requireResourceFile(graph, "sample.resources.MessagesListBundle_ko_KR")
            val listBundleBase = requireResourceFile(graph, "sample.resources.MessagesListBundle")
            val providerBundle = requireResourceFile(graph, "sample.resources.ProviderMessagesBundle_ko_KR")
            val providerBundleBase = requireResourceFile(graph, "sample.resources.ProviderMessagesBundle")

            val messageMethod = graph.methods(MethodPattern("sample.resources.ResourceConfig", "message")).single()
            val messageObjectMethod = graph.methods(MethodPattern("sample.resources.ResourceConfig", "messageObject")).single()
            val messageKeysMethod = graph.methods(MethodPattern("sample.resources.ResourceConfig", "messageKeys")).single()
            val messageKoMethod = graph.methods(MethodPattern("sample.resources.ResourceConfig", "messageKo")).single()
            val messageKoFromLocalMethod = graph.methods(MethodPattern("sample.resources.ResourceConfig", "messageKoFromLocal")).single()
            val messageKoFromTagMethod = graph.methods(MethodPattern("sample.resources.ResourceConfig", "messageKoFromTag")).single()
            val messageKoFromCtorMethod = graph.methods(MethodPattern("sample.resources.ResourceConfig", "messageKoFromCtor")).single()
            val messageKoWithClassLoaderMethod = graph.methods(MethodPattern("sample.resources.ResourceConfig", "messageKoWithClassLoader")).single()
            val messageKoWithControlMethod = graph.methods(MethodPattern("sample.resources.ResourceConfig", "messageKoWithControl")).single()
            val messageKoWithControlAliasMethod = graph.methods(MethodPattern("sample.resources.ResourceConfig", "messageKoWithControlAlias")).single()
            val messageKoWithDefaultControlAliasMethod = graph.methods(MethodPattern("sample.resources.ResourceConfig", "messageKoWithDefaultControlAlias")).single()
            val messageKoFromBuilderMethod = graph.methods(MethodPattern("sample.resources.ResourceConfig", "messageKoFromBuilder")).single()
            val messageKoFromBuilderTagMethod = graph.methods(MethodPattern("sample.resources.ResourceConfig", "messageKoFromBuilderTag")).single()
            val messageKoFromBuilderSetLocaleMethod = graph.methods(MethodPattern("sample.resources.ResourceConfig", "messageKoFromBuilderSetLocale")).single()
            val messageKoFromBuilderResetMethod = graph.methods(MethodPattern("sample.resources.ResourceConfig", "messageKoFromBuilderReset")).single()
            val messageKoFromBuilderVariantMethod = graph.methods(MethodPattern("sample.resources.ResourceConfig", "messageKoFromBuilderVariant")).single()
            val messageClassOnlyControlMethod = graph.methods(MethodPattern("sample.resources.ResourceConfig", "messageClassOnlyControlNoCandidate")).single()
            val messageClassOnlyCustomControlMethod = graph.methods(MethodPattern("sample.resources.ResourceConfig", "messageClassOnlyCustomControlNoCandidate")).single()
            val messageKoCustomCandidateControlMethod = graph.methods(MethodPattern("sample.resources.ResourceConfig", "messageKoWithCustomCandidateControl")).single()
            val propertyBundleMethod = graph.methods(MethodPattern("sample.resources.ResourceConfig", "messagePropertyBundle")).single()
            val propertyBundleReaderMethod = graph.methods(MethodPattern("sample.resources.ResourceConfig", "messagePropertyBundleReader")).single()
            val propertyBundleKeysMethod = graph.methods(MethodPattern("sample.resources.ResourceConfig", "messagePropertyBundleKeys")).single()
            val listBundleMessageMethod = graph.methods(MethodPattern("sample.resources.ResourceConfig", "listBundleMessage")).single()
            val listBundleKeysMethod = graph.methods(MethodPattern("sample.resources.ResourceConfig", "listBundleKeys")).single()
            val providerBundleMessageMethod = graph.methods(MethodPattern("sample.resources.ResourceConfig", "providerBundleMessage")).single()
            val providerBundleKeysMethod = graph.methods(MethodPattern("sample.resources.ResourceConfig", "providerBundleKeys")).single()

            val messageBundleCall = requireCallSite(graph, messageMethod, "getBundle")
            val messageLookupCall = requireCallSite(graph, messageMethod, "getString")
            val messageObjectCall = requireCallSite(graph, messageObjectMethod, "getObject")
            val messageKeysCall = requireCallSite(graph, messageKeysMethod, "getKeys")

            assertHasResourceEdge(graph.incomingResourceEdges(messageBundleCall.id), bundleRoot.id, ResourceRelation.BUNDLE_CANDIDATE, "messages.properties should be a ResourceBundle candidate")
            assertHasResourceEdge(graph.incomingResourceEdges(messageLookupCall.id), bundleRoot.id, ResourceRelation.LOOKUP, "messages.properties should link to ResourceBundle.getString")
            assertHasResourceEdge(graph.incomingResourceEdges(messageObjectCall.id), bundleRoot.id, ResourceRelation.LOOKUP, "messages.properties should link to ResourceBundle.getObject")
            assertHasResourceEdge(graph.incomingResourceEdges(messageKeysCall.id), bundleRoot.id, ResourceRelation.ENUMERATES, "messages.properties should enumerate into ResourceBundle.getKeys")

            assertLocaleAwareBundle(graph, messageKoMethod, bundleRoot, bundleKo, bundleKoKr)
            assertLocaleAwareBundle(graph, messageKoFromLocalMethod, bundleRoot, bundleKo, bundleKoKr)
            assertLocaleAwareBundle(graph, messageKoFromTagMethod, bundleRoot, bundleKo, bundleKoKr)
            assertLocaleAwareBundle(graph, messageKoFromCtorMethod, bundleRoot, bundleKo, bundleKoKr)
            assertLocaleAwareBundle(graph, messageKoWithClassLoaderMethod, bundleRoot, bundleKo, bundleKoKr)
            assertLocaleAwareBundle(graph, messageKoWithControlMethod, bundleRoot, bundleKo, bundleKoKr)
            assertLocaleAwareBundle(graph, messageKoWithControlAliasMethod, bundleRoot, bundleKo, bundleKoKr)
            assertLocaleAwareBundle(graph, messageKoWithDefaultControlAliasMethod, bundleRoot, bundleKo, bundleKoKr)
            assertLocaleAwareBundle(graph, messageKoFromBuilderMethod, bundleRoot, bundleKo, bundleKoKr)
            assertLocaleAwareBundle(graph, messageKoFromBuilderTagMethod, bundleRoot, bundleKo, bundleKoKr)
            assertLocaleAwareBundle(graph, messageKoFromBuilderSetLocaleMethod, bundleRoot, bundleKo, bundleKoKr)
            assertLocaleAwareBundle(graph, messageKoFromBuilderResetMethod, bundleRoot, bundleKo, bundleKoKr)
            assertLocaleAwareBundle(graph, messageKoFromBuilderVariantMethod, bundleRoot, bundleKo, bundleKoKr)

            val classOnlyControlCall = requireCallSite(graph, messageClassOnlyControlMethod, "getBundle")
            val classOnlyControlEdges = graph.incomingResourceEdges(classOnlyControlCall.id)
            assertTrue(classOnlyControlEdges.none { it.kind == ResourceRelation.BUNDLE_CANDIDATE }, "FORMAT_CLASS control should not point at .properties bundle files")

            val customClassOnlyControlCall = requireCallSite(graph, messageClassOnlyCustomControlMethod, "getBundle")
            val customClassOnlyControlEdges = graph.incomingResourceEdges(customClassOnlyControlCall.id)
            assertTrue(customClassOnlyControlEdges.none { it.kind == ResourceRelation.BUNDLE_CANDIDATE }, "Custom class-only control should not point at .properties bundle files")

            val customCandidateControlCall = requireCallSite(graph, messageKoCustomCandidateControlMethod, "getBundle")
            val customCandidateEdges = graph.incomingResourceEdges(customCandidateControlCall.id)
            assertHasResourceEdge(customCandidateEdges, bundleKoKr.id, ResourceRelation.BUNDLE_CANDIDATE, "Custom candidate control should keep ko_KR bundle candidate")
            assertTrue(customCandidateEdges.none { it.from == bundleKo.id && it.kind == ResourceRelation.BUNDLE_CANDIDATE }, "Custom candidate control should drop intermediate ko bundle")
            assertTrue(customCandidateEdges.none { it.from == bundleRoot.id && it.kind == ResourceRelation.BUNDLE_CANDIDATE }, "Custom candidate control should drop root bundle")

            val propertyBundleLookupCall = requireCallSite(graph, propertyBundleMethod, "getString")
            val propertyBundleReaderLookupCall = requireCallSite(graph, propertyBundleReaderMethod, "getString")
            val propertyBundleKeysCall = requireCallSite(graph, propertyBundleKeysMethod, "getKeys")
            assertHasResourceEdge(graph.incomingResourceEdges(propertyBundleLookupCall.id), bundleRoot.id, ResourceRelation.LOOKUP, "PropertyResourceBundle(InputStream) should link to messages.properties")
            assertHasResourceEdge(graph.incomingResourceEdges(propertyBundleReaderLookupCall.id), bundleRoot.id, ResourceRelation.LOOKUP, "PropertyResourceBundle(Reader) should link to messages.properties")
            assertHasResourceEdge(graph.incomingResourceEdges(propertyBundleKeysCall.id), bundleRoot.id, ResourceRelation.ENUMERATES, "PropertyResourceBundle.getKeys should enumerate messages.properties")

            val listBundleMessageCall = requireCallSite(graph, listBundleMessageMethod, "getString")
            val listBundleKeysCall = requireCallSite(graph, listBundleKeysMethod, "getKeys")
            assertHasResourceEdge(graph.incomingResourceEdges(listBundleMessageCall.id), listBundle.id, ResourceRelation.LOOKUP, "ListResourceBundle lookup should link by bundle class path")
            assertHasResourceEdge(graph.incomingResourceEdges(listBundleKeysCall.id), listBundleBase.id, ResourceRelation.ENUMERATES, "ListResourceBundle getKeys should enumerate base bundle path")

            val providerBundleMessageCall = requireCallSite(graph, providerBundleMessageMethod, "getString")
            val providerBundleKeysCall = requireCallSite(graph, providerBundleKeysMethod, "getKeys")
            assertHasResourceEdge(graph.incomingResourceEdges(providerBundleMessageCall.id), providerBundle.id, ResourceRelation.LOOKUP, "Provider-backed bundle lookup should link by bundle class path")
            assertHasResourceEdge(graph.incomingResourceEdges(providerBundleKeysCall.id), providerBundleBase.id, ResourceRelation.ENUMERATES, "Provider-backed bundle getKeys should enumerate base bundle path")
        } finally {
            fixtureDir.toFile().deleteRecursively()
        }
    }

    private fun assertLocaleAwareBundle(
        graph: Graph,
        method: io.johnsonlee.graphite.core.MethodDescriptor,
        root: ResourceFileNode,
        language: ResourceFileNode,
        locale: ResourceFileNode
    ) {
        val bundleCall = requireCallSite(graph, method, "getBundle")
        val lookupCall = requireCallSite(graph, method, "getString")
        val bundleEdges = graph.incomingResourceEdges(bundleCall.id)
        assertHasResourceEdge(bundleEdges, locale.id, ResourceRelation.BUNDLE_CANDIDATE, "${method.name} should keep ko_KR candidate")
        assertHasResourceEdge(bundleEdges, language.id, ResourceRelation.BUNDLE_CANDIDATE, "${method.name} should keep ko candidate")
        assertHasResourceEdge(bundleEdges, root.id, ResourceRelation.BUNDLE_CANDIDATE, "${method.name} should keep root candidate")
        assertHasResourceEdge(graph.incomingResourceEdges(lookupCall.id), locale.id, ResourceRelation.LOOKUP, "${method.name} should link lookup to ko_KR bundle path")
    }

    private fun requireCallSite(graph: Graph, method: io.johnsonlee.graphite.core.MethodDescriptor, calleeName: String): CallSiteNode =
        graph.nodes(CallSiteNode::class.java)
            .filter { it.caller == method && it.callee.name == calleeName }
            .singleOrNull()
            .also { assertNotNull(it, "Should create call site for ${method.signature} -> $calleeName") }!!

    private fun requireResourceFile(graph: Graph, path: String): ResourceFileNode =
        graph.nodes(ResourceFileNode::class.java)
            .firstOrNull { it.path == path }
            .also { assertNotNull(it, "Should create ResourceFileNode for $path") }!!

    private fun createFixtureDir(): Path {
        val compiledClass = findCompiledTestClass("sample/resources/ResourceConfig.class")
        val compiledMessagesListBundleClass = findCompiledTestClass("sample/resources/MessagesListBundle.class")
        val compiledMessagesListBundleKoClass = findCompiledTestClass("sample/resources/MessagesListBundle_ko_KR.class")
        val compiledProviderBundleClass = findCompiledTestClass("sample/resources/ProviderMessagesBundle.class")
        val compiledProviderBundleKoClass = findCompiledTestClass("sample/resources/ProviderMessagesBundle_ko_KR.class")
        val compiledProviderClass = findCompiledTestClass("sample/resources/MessagesProvider.class")
        val compiledClassOnlyControl = findCompiledTestClass("sample/resources/ClassOnlyControl.class")
        val compiledKoreanOnlyControl = findCompiledTestClass("sample/resources/KoreanOnlyControl.class")
        val providerService = findCompiledTestResource("META-INF/services/java.util.spi.ResourceBundleProvider")
        val fixtureDir = Files.createTempDirectory("graphite-resource-config")
        val targetClass = fixtureDir.resolve("sample/resources/ResourceConfig.class")
        Files.createDirectories(targetClass.parent)
        Files.copy(compiledClass, targetClass)
        Files.copy(compiledMessagesListBundleClass, fixtureDir.resolve("sample/resources/MessagesListBundle.class"))
        Files.copy(compiledMessagesListBundleKoClass, fixtureDir.resolve("sample/resources/MessagesListBundle_ko_KR.class"))
        Files.copy(compiledProviderBundleClass, fixtureDir.resolve("sample/resources/ProviderMessagesBundle.class"))
        Files.copy(compiledProviderBundleKoClass, fixtureDir.resolve("sample/resources/ProviderMessagesBundle_ko_KR.class"))
        Files.copy(compiledProviderClass, fixtureDir.resolve("sample/resources/MessagesProvider.class"))
        Files.copy(compiledClassOnlyControl, fixtureDir.resolve("sample/resources/ClassOnlyControl.class"))
        Files.copy(compiledKoreanOnlyControl, fixtureDir.resolve("sample/resources/KoreanOnlyControl.class"))
        val serviceTarget = fixtureDir.resolve("META-INF/services/java.util.spi.ResourceBundleProvider")
        Files.createDirectories(serviceTarget.parent)
        Files.copy(providerService, serviceTarget)
        Files.writeString(
            fixtureDir.resolve("application.properties"),
            """
            feature.mode=shadow
            feature.enabled=true
            """.trimIndent()
        )
        Files.writeString(
            fixtureDir.resolve("application.yml"),
            """
            server:
              port: 8080
            feature:
              enabled: true
              ttl: 30
            """.trimIndent()
        )
        Files.writeString(
            fixtureDir.resolve("application.json"),
            """
            {
              "feature": {
                "enabled": true,
                "modes": ["light", "dark"]
              },
              "server": {
                "port": 9090
              }
            }
            """.trimIndent()
        )
        Files.writeString(
            fixtureDir.resolve("messages.properties"),
            """
            hello=world
            """.trimIndent()
        )
        Files.writeString(
            fixtureDir.resolve("messages_ko.properties"),
            """
            hello=annyeong
            """.trimIndent()
        )
        Files.writeString(
            fixtureDir.resolve("messages_ko_KR.properties"),
            """
            hello=annyeong-hanguk
            """.trimIndent()
        )
        Files.writeString(
            fixtureDir.resolve("application.xml"),
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE properties SYSTEM "http://java.sun.com/dtd/properties.dtd">
            <properties>
              <entry key="feature.mode">xml-shadow</entry>
            </properties>
            """.trimIndent()
        )
        Files.writeString(
            fixtureDir.resolve("config.xml"),
            """
            <config>
              <service enabled="true">
                <endpoint>https://api.example.com</endpoint>
                <timeout>30</timeout>
              </service>
            </config>
            """.trimIndent()
        )
        return fixtureDir
    }

    private fun findCompiledTestClass(relativePath: String): Path {
        val projectDir = Path.of(System.getProperty("user.dir"))
        val submodulePath = projectDir.resolve("build/classes/java/test").resolve(relativePath)
        val rootPath = projectDir.resolve("graphite-sootup/build/classes/java/test").resolve(relativePath)
        val classFile = if (submodulePath.exists()) submodulePath else rootPath
        assertTrue(classFile.exists(), "Compiled test class should exist: $classFile")
        return classFile
    }

    private fun findCompiledTestResource(relativePath: String): Path {
        val projectDir = Path.of(System.getProperty("user.dir"))
        val submodulePath = projectDir.resolve("build/resources/test").resolve(relativePath)
        val rootPath = projectDir.resolve("graphite-sootup/build/resources/test").resolve(relativePath)
        val resourceFile = if (submodulePath.exists()) submodulePath else rootPath
        assertTrue(resourceFile.exists(), "Compiled test resource should exist: $resourceFile")
        return resourceFile
    }

    private fun Graph.incomingResourceEdges(nodeId: NodeId): List<ResourceEdge> =
        incoming(nodeId, ResourceEdge::class.java).toList()

    private fun assertHasResourceEdge(
        edges: List<ResourceEdge>,
        from: NodeId,
        kind: ResourceRelation,
        message: String
    ) {
        assertTrue(edges.any { it.from == from && it.kind == kind }, message)
    }
}
