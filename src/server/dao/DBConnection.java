package server.dao;

import java.sql.*;

public class DBConnection {
	private static final String URL =
		    "jdbc:mysql://localhost:3306/test"
		  + "?useSSL=false"
		  + "&allowPublicKeyRetrieval=true"
		  + "&serverTimezone=UTC";
    private static final String USER = "root";
    private static final String PASS = "";

    public static Connection get() throws SQLException {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            throw new SQLException("MySQL driver not found", e);
        }
        return DriverManager.getConnection(URL, USER, PASS);
    }
}
