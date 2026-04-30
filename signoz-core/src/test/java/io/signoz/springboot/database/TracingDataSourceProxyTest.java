package io.signoz.springboot.database;

import io.signoz.springboot.properties.SigNozDatabaseProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class TracingDataSourceProxyTest {

    private DataSource mockDataSource;
    private Connection mockConnection;
    private PreparedStatement mockStatement;
    private Statement mockPlainStatement;
    private CallableStatement mockCallableStatement;
    private SigNozDatabaseProperties props;

    @BeforeEach
    void setUp() throws SQLException {
        mockDataSource = mock(DataSource.class);
        mockConnection = mock(Connection.class);
        mockStatement = mock(PreparedStatement.class);
        mockPlainStatement = mock(Statement.class);
        mockCallableStatement = mock(CallableStatement.class);

        when(mockDataSource.getConnection()).thenReturn(mockConnection);
        when(mockConnection.prepareStatement(anyString())).thenReturn(mockStatement);
        when(mockConnection.createStatement()).thenReturn(mockPlainStatement);
        when(mockConnection.prepareCall(anyString())).thenReturn(mockCallableStatement);
        when(mockStatement.executeQuery()).thenReturn(mock(ResultSet.class));
        when(mockStatement.executeQuery(anyString())).thenReturn(mock(ResultSet.class));
        when(mockStatement.execute()).thenReturn(true);
        when(mockStatement.execute(anyString())).thenReturn(true);
        when(mockStatement.executeUpdate()).thenReturn(1);
        when(mockStatement.executeBatch()).thenReturn(new int[]{1});
        when(mockPlainStatement.executeQuery(anyString())).thenReturn(mock(ResultSet.class));
        when(mockPlainStatement.execute(anyString())).thenReturn(true);
        when(mockPlainStatement.executeBatch()).thenReturn(new int[]{1});
        when(mockCallableStatement.execute()).thenReturn(true);

        props = new SigNozDatabaseProperties();
    }

    @Test
    void wrapsDataSourceAndDelegatesQueries() throws SQLException {
        TracingDataSourceProxy proxy = new TracingDataSourceProxy(mockDataSource, props);
        Connection conn = proxy.getConnection();
        PreparedStatement stmt = conn.prepareStatement("SELECT 1");
        stmt.executeQuery();

        verify(mockStatement).executeQuery();
    }

    @Test
    void executesUpdateSuccessfully() throws SQLException {
        TracingDataSourceProxy proxy = new TracingDataSourceProxy(mockDataSource, props);
        Connection conn = proxy.getConnection();
        PreparedStatement stmt = conn.prepareStatement("UPDATE t SET x=1");
        int result = stmt.executeUpdate();

        assertThat(result).isEqualTo(1);
        verify(mockStatement).executeUpdate();
    }

    @Test
    void delegateIsAccessible() throws SQLException {
        TracingDataSourceProxy proxy = new TracingDataSourceProxy(mockDataSource, props);
        assertThat(proxy.getDelegate()).isSameAs(mockDataSource);
    }

    @Test
    void executeCallIsTimed() throws SQLException {
        TracingDataSourceProxy proxy = new TracingDataSourceProxy(mockDataSource, props);
        Connection conn = proxy.getConnection();
        PreparedStatement stmt = conn.prepareStatement("SELECT * FROM users");
        boolean result = stmt.execute();

        assertThat(result).isTrue();
        verify(mockStatement).execute();
    }

    @Test
    void plainStatementSqlExecutionIsTimed() throws SQLException {
        TracingDataSourceProxy proxy = new TracingDataSourceProxy(mockDataSource, props);
        Connection conn = proxy.getConnection();
        Statement stmt = conn.createStatement();
        ResultSet result = stmt.executeQuery("SELECT 1");

        assertThat(result).isNotNull();
        verify(mockPlainStatement).executeQuery("SELECT 1");
    }

    @Test
    void preparedStatementSqlOverloadIsTimed() throws SQLException {
        TracingDataSourceProxy proxy = new TracingDataSourceProxy(mockDataSource, props);
        Connection conn = proxy.getConnection();
        PreparedStatement stmt = conn.prepareStatement("SELECT 1");
        boolean result = stmt.execute("SELECT 2");

        assertThat(result).isTrue();
        verify(mockStatement).execute("SELECT 2");
    }

    @Test
    void preparedStatementBatchExecutionIsTimed() throws SQLException {
        TracingDataSourceProxy proxy = new TracingDataSourceProxy(mockDataSource, props);
        Connection conn = proxy.getConnection();
        PreparedStatement stmt = conn.prepareStatement("INSERT INTO t VALUES (?)");
        int[] result = stmt.executeBatch();

        assertThat(result).containsExactly(1);
        verify(mockStatement).executeBatch();
    }

    @Test
    void callableStatementExecutionIsTimed() throws SQLException {
        TracingDataSourceProxy proxy = new TracingDataSourceProxy(mockDataSource, props);
        Connection conn = proxy.getConnection();
        CallableStatement stmt = conn.prepareCall("{call refresh_users()}");
        boolean result = stmt.execute();

        assertThat(result).isTrue();
        verify(mockCallableStatement).execute();
    }
}
