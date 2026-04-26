package io.johnsonlee.graphite.c4

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class C4TagsTest {

    @Test
    fun `classifies RestController as API`() {
        val annotations = mapOf(
            "org.springframework.web.bind.annotation.RestController" to emptyMap<String, Any?>()
        )
        assertEquals(C4Tags.Stereotype.API, C4Tags.classifyStereotype(annotations))
    }

    @Test
    fun `classifies Controller as API`() {
        val annotations = mapOf(
            "org.springframework.stereotype.Controller" to emptyMap<String, Any?>()
        )
        assertEquals(C4Tags.Stereotype.API, C4Tags.classifyStereotype(annotations))
    }

    @Test
    fun `classifies class-level RequestMapping as API`() {
        val annotations = mapOf(
            "org.springframework.web.bind.annotation.RequestMapping" to mapOf("value" to "/v1")
        )
        assertEquals(C4Tags.Stereotype.API, C4Tags.classifyStereotype(annotations))
    }

    @Test
    fun `classifies Service`() {
        val annotations = mapOf("org.springframework.stereotype.Service" to emptyMap<String, Any?>())
        assertEquals(C4Tags.Stereotype.SERVICE, C4Tags.classifyStereotype(annotations))
    }

    @Test
    fun `classifies Repository`() {
        val annotations = mapOf("org.springframework.stereotype.Repository" to emptyMap<String, Any?>())
        assertEquals(C4Tags.Stereotype.DATA_ACCESS, C4Tags.classifyStereotype(annotations))
    }

    @Test
    fun `classifies Configuration`() {
        val annotations = mapOf("org.springframework.context.annotation.Configuration" to emptyMap<String, Any?>())
        assertEquals(C4Tags.Stereotype.CONFIG, C4Tags.classifyStereotype(annotations))
    }

    @Test
    fun `returns null when no rule matches`() {
        assertNull(C4Tags.classifyStereotype(emptyMap()))
        assertNull(C4Tags.classifyStereotype(mapOf("javax.annotation.Nullable" to emptyMap<String, Any?>())))
    }

    @Test
    fun `classifies external system labels`() {
        assertEquals(C4Tags.ExternalSystem.DATABASE, C4Tags.classifyExternalSystem("java.sql.Connection"))
        assertEquals(C4Tags.ExternalSystem.DATABASE, C4Tags.classifyExternalSystem("javax.persistence.EntityManager"))
        assertEquals(C4Tags.ExternalSystem.DATABASE, C4Tags.classifyExternalSystem("jakarta.persistence.EntityManager"))
        assertEquals(C4Tags.ExternalSystem.DATABASE, C4Tags.classifyExternalSystem("org.springframework.data.jpa.repository.JpaRepository"))
        assertEquals(C4Tags.ExternalSystem.REDIS, C4Tags.classifyExternalSystem("org.springframework.data.redis.core.RedisTemplate"))
        assertEquals(C4Tags.ExternalSystem.KAFKA, C4Tags.classifyExternalSystem("org.apache.kafka.clients.producer.KafkaProducer"))
        assertEquals(C4Tags.ExternalSystem.RABBITMQ, C4Tags.classifyExternalSystem("com.rabbitmq.client.Channel"))
        assertEquals(C4Tags.ExternalSystem.EXTERNAL_HTTP_API, C4Tags.classifyExternalSystem("org.springframework.web.client.RestTemplate"))
        assertEquals(C4Tags.ExternalSystem.EXTERNAL_HTTP_API, C4Tags.classifyExternalSystem("okhttp3.OkHttpClient"))
        assertEquals(C4Tags.ExternalSystem.ELASTICSEARCH, C4Tags.classifyExternalSystem("co.elastic.clients.elasticsearch.ElasticsearchClient"))
        assertEquals(C4Tags.ExternalSystem.AWS, C4Tags.classifyExternalSystem("software.amazon.awssdk.services.s3.S3Client"))
        assertNull(C4Tags.classifyExternalSystem("com.example.UserService"))
        assertNull(C4Tags.classifyExternalSystem("java.util.List"))
    }
}
