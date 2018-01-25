package com.r3.corda.networkmanage.hsm.generator

import com.r3.corda.networkmanage.hsm.authentication.CryptoServerProviderConfig
import com.r3.corda.networkmanage.hsm.utils.mapCryptoServerException
import net.corda.nodeapi.internal.crypto.CertificateType.ROOT_CA
import org.apache.logging.log4j.LogManager

private val log = LogManager.getLogger("com.r3.corda.networkmanage.hsm.generator.Main")

fun main(args: Array<String>) {
    run(parseParameters(parseCommandLine(*args).configFile))
}

fun run(parameters: GeneratorParameters) {
    parameters.run {
        val providerConfig = CryptoServerProviderConfig(
                Device = "$hsmPort@$hsmHost",
                KeySpecifier = certConfig.keySpecifier,
                KeyGroup = certConfig.keyGroup,
                StoreKeysExternal = certConfig.storeKeysExternal)
        try {
            AutoAuthenticator(providerConfig, userConfigs).connectAndAuthenticate { provider ->
                val generator = KeyCertificateGenerator(this)
                if (certConfig.certificateType == ROOT_CA) {
                    generator.generate(provider)
                } else {
                    requireNotNull(certConfig.rootKeyGroup)
                    val rootProviderConfig = CryptoServerProviderConfig(
                            Device = "$hsmPort@$hsmHost",
                            KeySpecifier = certConfig.keySpecifier,
                            KeyGroup = certConfig.rootKeyGroup!!,
                            StoreKeysExternal = certConfig.storeKeysExternal)
                    AutoAuthenticator(rootProviderConfig, userConfigs).connectAndAuthenticate { rootProvider ->
                        generator.generate(provider, rootProvider)
                        rootProvider.logoff()
                    }
                }
                provider.logoff()
            }
        } catch (e: Exception) {
            log.error(mapCryptoServerException(e))
        }
    }
}