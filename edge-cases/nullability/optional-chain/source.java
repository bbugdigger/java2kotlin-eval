package edge.nullability.optional_chain;

public class Address {
    private String city;
    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }
}

class Owner {
    private Address address;
    public Address getAddress() { return address; }
    public void setAddress(Address address) { this.address = address; }

    public String cityName() {
        if (address != null && address.getCity() != null) {
            return address.getCity();
        }
        return "unknown";
    }
}
