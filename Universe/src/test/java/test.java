import java.math.RoundingMode;
import java.text.DecimalFormat;

public class test {
    public static void main(String[] args) {
        DecimalFormat df = new DecimalFormat("0.0000");
        df.setRoundingMode(RoundingMode.HALF_UP);
        System.out.println(df.format(111.111112341234));
    }
}
