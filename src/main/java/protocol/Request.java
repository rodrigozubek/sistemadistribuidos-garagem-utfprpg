/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package protocol;
import com.google.gson.JsonObject;

/**
 *
 * @author Rodrigo
 */
public class Request {

    private String method;
    private JsonObject data;

    public String getMethod() {
        return method;
    }

    public JsonObject getData() {
        return data;
    }
}