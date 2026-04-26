package io.johnsonlee.graphite.c4

/**
 * Build-time C4 taxonomy: synthetic annotation FQNs and the rules that derive
 * stereotype/external-system labels from class metadata.
 *
 * Tags are written into a class's `<class>` member-annotation map using these
 * FQNs as keys, so they round-trip through [io.johnsonlee.graphite.graph.Graph.memberAnnotations]
 * and [io.johnsonlee.graphite.webgraph.GraphStore] without schema changes.
 */
object C4Tags {

    /** Synthetic annotation FQN for class stereotype. */
    const val STEREOTYPE_ANNOTATION: String = "io.johnsonlee.graphite.c4.Stereotype"

    /** Synthetic annotation FQN for external-system marker. */
    const val EXTERNAL_SYSTEM_ANNOTATION: String = "io.johnsonlee.graphite.c4.ExternalSystem"

    /** Standard attribute name for both synthetic annotations. */
    const val VALUE_ATTRIBUTE: String = "value"

    /** Stereotype labels. */
    object Stereotype {
        const val API: String = "API"
        const val SERVICE: String = "Service"
        const val DATA_ACCESS: String = "Data Access"
        const val CONFIG: String = "Config"
    }

    /** External-system labels. */
    object ExternalSystem {
        const val DATABASE: String = "Database"
        const val REDIS: String = "Redis"
        const val KAFKA: String = "Kafka"
        const val RABBITMQ: String = "RabbitMQ"
        const val EXTERNAL_HTTP_API: String = "External HTTP API"
        const val ELASTICSEARCH: String = "Elasticsearch"
        const val AWS: String = "AWS"
    }

    private val stereotypeRules: List<Pair<Set<String>, String>> = listOf(
        setOf(
            "org.springframework.web.bind.annotation.RestController",
            "org.springframework.stereotype.Controller",
            "org.springframework.web.bind.annotation.RequestMapping"
        ) to Stereotype.API,
        setOf(
            "org.springframework.stereotype.Service"
        ) to Stereotype.SERVICE,
        setOf(
            "org.springframework.stereotype.Repository"
        ) to Stereotype.DATA_ACCESS,
        setOf(
            "org.springframework.context.annotation.Configuration"
        ) to Stereotype.CONFIG
    )

    private val externalSystemRules: List<Pair<List<String>, String>> = listOf(
        listOf(
            "java.sql.",
            "javax.sql.",
            "javax.persistence.",
            "jakarta.persistence.",
            "org.hibernate.",
            "org.springframework.data.jpa.",
            "org.springframework.jdbc.",
            "org.mybatis.",
            "com.mongodb."
        ) to ExternalSystem.DATABASE,
        listOf(
            "org.springframework.data.redis.",
            "redis.clients."
        ) to ExternalSystem.REDIS,
        listOf(
            "org.springframework.kafka.",
            "org.apache.kafka."
        ) to ExternalSystem.KAFKA,
        listOf(
            "org.springframework.amqp.",
            "com.rabbitmq."
        ) to ExternalSystem.RABBITMQ,
        listOf(
            "org.springframework.web.client.",
            "org.springframework.web.reactive.function.client.",
            "org.springframework.cloud.openfeign.",
            "java.net.http.",
            "okhttp3.",
            "feign."
        ) to ExternalSystem.EXTERNAL_HTTP_API,
        listOf(
            "org.elasticsearch.",
            "co.elastic.clients."
        ) to ExternalSystem.ELASTICSEARCH,
        listOf(
            "software.amazon.awssdk.",
            "com.amazonaws."
        ) to ExternalSystem.AWS
    )

    /**
     * Classify a class's stereotype based on its `<class>`-level annotations.
     * Returns null when no Spring stereotype annotation is present.
     */
    fun classifyStereotype(annotations: Map<String, Map<String, Any?>>): String? {
        if (annotations.isEmpty()) return null
        for ((annotationFqns, label) in stereotypeRules) {
            if (annotationFqns.any { it in annotations }) return label
        }
        return null
    }

    /**
     * Classify an external-system label for a class FQN based on package prefix.
     * Returns null when no rule matches.
     */
    fun classifyExternalSystem(className: String): String? {
        for ((prefixes, label) in externalSystemRules) {
            if (prefixes.any { className.startsWith(it) }) return label
        }
        return null
    }
}
