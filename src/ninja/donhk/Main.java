package ninja.donhk;

public class Main {

    public static void main(String[] args) {
        VBoxManager box = new VBoxManager();
        System.out.println("VirtualBox version: " + box.getVBoxVersion());
    }
}
