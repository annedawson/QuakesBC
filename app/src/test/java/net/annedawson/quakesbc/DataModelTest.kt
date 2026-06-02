package net.annedawson.quakesbc

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DataModelTest {
    private val gson = Gson()

    @Test
    fun `test properties handles null magnitude and place`() {
        // JSON where 'mag' and 'place' are explicitly null or missing
        val json = """
            {
              "features": [
                {
                  "id": "nc74011836",
                  "properties": {
                    "mag": null,
                    "place": null,
                    "time": 1684257120000
                  },
                  "geometry": {
                    "coordinates": [-122.4, 37.8, 5.0]
                  }
                }
              ]
            }
        """.trimIndent()

        val response = gson.fromJson(json, EarthquakeResponse::class.java)
        val properties = response.features[0].properties

        // Assertions
        assertNull("Magnitude should be null", properties.mag)
        assertNull("Place should be null", properties.place)
        assertEquals(1684257120000L, properties.time)
    }

    @Test
    fun `test properties handles missing felt field`() {
        // JSON where 'felt' is completely missing from the object
        val json = """
            {
              "features": [
                {
                  "id": "abc123",
                  "properties": {
                    "mag": 2.5,
                    "time": 1684257120000
                  },
                  "geometry": { "coordinates": [0, 0] }
                }
              ]
            }
        """.trimIndent()

        val response = gson.fromJson(json, EarthquakeResponse::class.java)

        // GSON will set missing optional fields to null if they are nullable in Kotlin
        assertNull("Missing felt field should result in null", response.features[0].properties.felt)
    }
}
