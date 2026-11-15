package ro.ppc.filegrid.security;

public class LoginFailedException extends RuntimeException {

    public LoginFailedException() {
        super("Utilizator sau parola gresita.");
    }
}
