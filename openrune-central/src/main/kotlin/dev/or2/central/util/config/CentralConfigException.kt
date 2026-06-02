package dev.or2.central.util.config

/** Missing or invalid `central-config.yaml` / environment configuration. */
class CentralConfigException(message: String) : IllegalStateException(message)
