package br.edu.utfpr.sistemasdistribuidos.sistemagaragem;

import java.util.Arrays;
import client.Client;
import server.Server;

public class SistemaGaragem {
    public static void main(String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("server")) {
            Server.main(Arrays.copyOfRange(args, 1, args.length));
        } else {
            Client.main(args.length > 0 && args[0].equalsIgnoreCase("client")
                    ? Arrays.copyOfRange(args, 1, args.length) : args);
        }
    }
}
