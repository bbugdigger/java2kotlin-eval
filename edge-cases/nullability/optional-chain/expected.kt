package edge.nullability.optional_chain

class Address {
    var city: String? = null
}

class Owner {
    var address: Address? = null

    fun cityName(): String = address?.city ?: "unknown"
}
