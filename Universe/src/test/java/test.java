import com.nekoyu.Universe.API.MessageChannel.MessageField.ImageField;
import com.nekoyu.Universe.API.MessageChannel.MessageField.MsgField;

import java.math.RoundingMode;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.DecimalFormat;

public class test {
    public static void main(String[] args) throws MalformedURLException {
        ImageField imageField = new ImageField(new URL("https://srzki.com/"));
        System.out.println(imageField.toString());
        System.out.println(imageField);
        MsgField mf = imageField;
        System.out.println(mf.toString());
    }
}
