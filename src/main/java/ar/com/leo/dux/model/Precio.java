package ar.com.leo.dux.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public class Precio {

    @JsonProperty("id")
    private int id;

    @JsonProperty("nombre")
    private String nombre;

    @JsonProperty("precio")
    private String precio;

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getPrecio() {
        return precio;
    }

    public void setPrecio(String precio) {
        this.precio = precio;
    }

    public String getNombre() {
        return nombre;
    }

    public void setNombre(String nombre) {
        this.nombre = nombre;
    }

}
