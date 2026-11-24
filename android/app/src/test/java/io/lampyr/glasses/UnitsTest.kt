package io.lampyr.glasses

import io.lampyr.glasses.data.GatewayMappings
import io.lampyr.glasses.data.Weather
import io.lampyr.glasses.ui.screens.healthValue
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/** The gateway's `units` setting (imperial or metric) reaches the weather label and weight values. */
class UnitsTest {
    @Test
    fun weatherLabelFollowsTheGatewayUnit() {
        assertEquals("22°C", Weather(tempF = 72, temp = 22, unit = "C").temperatureLabel)
        assertEquals("an older gateway sends only tempF", "72°F", Weather(tempF = 72).temperatureLabel)
        val home = GatewayMappings.home(Json.parseToJsonElement("""{"weather":{"temp":21.6,"unit":"C","tempF":71,"condition":"cloudy","icon":"cloud"}}"""))
        assertEquals("21°C", home.weather.temperatureLabel)
    }

    @Test
    fun weightFormatsInItsOwnUnit() {
        assertEquals("79.4 kg", healthValue(79.4, "kg"))
        assertEquals("165.4 lb", healthValue(165.4, "lb"))
    }
}
