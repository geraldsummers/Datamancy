package config

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.Constructor
import services.ServicesConfig
import java.io.InputStream

object ConfigLoader {
    fun loadServices(input: InputStream): ServicesConfig {
        val loaderOptions = LoaderOptions()
        val constructor = Constructor(ServicesConfig::class.java, loaderOptions)
        val yaml = Yaml(constructor)
        return yaml.loadAs(input, ServicesConfig::class.java) ?: ServicesConfig()
    }
}
