package edge.property_folding.setter_only

class WriteOnly {
    private var secret: String? = null
        set(value) {
            field = value
        }

    fun setSecret(secret: String?) {
        this.secret = secret
    }
}
