package io.signoz.springboot.database;

import io.signoz.springboot.properties.SigNozDatabaseProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class TracingDataSourceProxyTest {

    private DataSource mockDataSource;
    private Connection mockConnection;
    private PreparedStatement mockStatement;
    private SigNozDatabaseProperties props;

    @BeforeEach
    void setUp() throws SQLException {
        mockDataSource = mock(DataSource.class);
        mockConnection = mock(Connection.class);
        mockStatement = mock(PreparedStatement.class);

        when(mockDataSource.getConnection()).thenReturn(mockConnection);
        when(mockConnection.prepareStatement(anyString())).thenReturn(mockStatement);
        when(mockStatement.executeQuery()).thenReturn(mock(ResultSet.class));
        when(mockStatement.execute()).thenReturn(true);
        when(mockStatement.executeUpdate()).thenReturn(1);

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
}
