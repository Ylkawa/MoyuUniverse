import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class test {
    public static void main(String[] args) {
        String url = "jdbc:mysql://47.103.86.44:3306/www_srzki_com?useSSL=true&serverTimezone=UTC";
        String username = "www_srzki_com";
        String password = "yBb8hTc2S65mH2KH";

        try {
            Connection connection = DriverManager.getConnection(url, username, password);
            System.out.println("数据库连接成功！");
        } catch (SQLException e) {
            System.out.println("数据库连接失败");
            e.printStackTrace();
        }
    }
}
