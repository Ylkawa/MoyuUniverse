import com.nekoyu.Universe.API.MessageChannel.MCMessage;

public class t {
    public static void main(String[] args) {
        MCMessage mcm = new MCMessage();
        mcm.sender.setPlatform("QQ");
        mcm.sender.setName("lost melody");
        mcm.sender.setId("546379");
        System.out.println(mcm.sender.getLocationId());
        System.out.println(mcm.getLocationId());
    }
}
