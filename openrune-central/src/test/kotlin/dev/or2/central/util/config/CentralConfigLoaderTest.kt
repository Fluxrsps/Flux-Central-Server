package dev.or2.central.util.config

import kotlin.test.Test
import kotlin.test.assertEquals

class CentralConfigLoaderTest {

    @Test
    fun `yaml layer overrides env layer when both set`() {
        val env =
            mapOf(
                CentralConfigKey.HTTP_PORT to "9000",
                CentralConfigKey.DB_USER to "envuser",
                CentralConfigKey.DB_PASSWORD to "envpass",
            )
        val yaml =
            mapOf(
                CentralConfigKey.HTTP_PORT to "8080",
                CentralConfigKey.DB_USER to "yamluser",
            )

        val (merged, overrides) = mergeConfigLayers(env, yaml)

        assertEquals("8080", merged[CentralConfigKey.HTTP_PORT])
        assertEquals("yamluser", merged[CentralConfigKey.DB_USER])
        assertEquals("envpass", merged[CentralConfigKey.DB_PASSWORD])
        assertEquals(2, overrides.size)
    }

    @Test
    fun `flatten yaml nested paths`() {
        val flat =
            flattenYamlNode(
                mapOf(
                    "openrune" to
                        mapOf(
                            "http" to mapOf("port" to 8080),
                            "db" to mapOf("host" to "localhost"),
                        ),
                ),
            )
        assertEquals("8080", flat["openrune.http.port"])
        assertEquals("localhost", flat["openrune.db.host"])
    }
}
