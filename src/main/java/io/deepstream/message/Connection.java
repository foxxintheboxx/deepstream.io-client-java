package io.deepstream.message;

import io.deepstream.ConnectionChangeListener;
import io.deepstream.DeepstreamClient;
import io.deepstream.LoginCallback;
import io.deepstream.constants.*;
import org.json.JSONObject;

import java.net.URISyntaxException;
import java.util.*;

public class Connection {

    Endpoint endpoint;

    private DeepstreamClient client;
    private String originalUrl;
    private ConnectionState connectionState;
    private ArrayList<ConnectionChangeListener> connectStateListeners;

    private boolean tooManyAuthAttempts;
    private StringBuilder messageBuffer;

    private LoginCallback loginCallback;
    private JSONObject authParameters;
    private Map options;

    public Connection(final String url, final Map options, DeepstreamClient client ) throws Exception {
        this( url, options, client, null );
        this.endpoint = createEndpoint(url, options);
    }

    Connection(final String url, final Map options, DeepstreamClient client, Endpoint endpoint ) {
        this.client = client;
        this.connectStateListeners = new ArrayList<>();
        this.originalUrl = url;
        this.connectionState = ConnectionState.CLOSED;
        this.messageBuffer = new StringBuilder();
        this.tooManyAuthAttempts = false;
        this.options = options;
        this.endpoint = endpoint;
    }

    public void authenticate( JSONObject authParameters, LoginCallback loginCallback ) throws Exception {
        if( this.tooManyAuthAttempts ) {
            this.client.onError( Topic.ERROR, Event.IS_CLOSED, "the client\'s connection was closed" );
            return;
        }
        this.loginCallback = loginCallback;
        this.authParameters = authParameters;

        if( this.connectionState == ConnectionState.AWAITING_AUTHENTICATION ) {
            this.setState( ConnectionState.AUTHENTICATING );
            this.sendAuthMessage();
        }
    }

    public void send( String message ) {
        if( this.connectionState != ConnectionState.OPEN ) {
            this.messageBuffer.append( message );
            System.out.println( "Buffering " + message );
        } else {
            System.out.println( "Sending " + message );
            this.endpoint.send( message );
        }
    }

    private void sendAuthMessage() {
        String authMessage = MessageBuilder.getMsg( Topic.AUTH, Actions.REQUEST, this.authParameters.toString() );
        this.endpoint.send( authMessage );
    }

    public void addConnectionChangeListener( ConnectionChangeListener connectionChangeListener ) {
        this.connectStateListeners.add( connectionChangeListener );
    }

    public void removeConnectionChangeListener( ConnectionChangeListener connectionChangeListener ) {
        this.connectStateListeners.remove( connectionChangeListener );
    }

    public ConnectionState getConnectionState() {
        return this.connectionState;
    }

    void onOpen() {
        this.setState( ConnectionState.AWAITING_CONNECTION );
    }

    void onError(Exception exception) {
        System.out.println( exception );
    }

    void onMessage(String rawMessage) {
        List<Message> parsedMessages = MessageParser.parse( rawMessage, this );
        for (Message message : parsedMessages) {
            if (message.topic == Topic.CONNECTION) {
                handleConnectionResponse(message);
            } else if (message.topic == Topic.AUTH) {
                handleAuthResponse(message);
            } else if (message.topic == Topic.EVENT) {
                this.client.event.handle(message);
            } else {
                System.out.println("Normal message of type " + message.topic);
            }
        }
    }

    private void handleConnectionResponse( Message message ) {
        if( message.action == Actions.ACK ) {
            this.setState( ConnectionState.AWAITING_AUTHENTICATION );
        }
        else if( message.action == Actions.CHALLENGE ) {
            this.setState( ConnectionState.CHALLENGING );
            this.endpoint.send( MessageBuilder.getMsg( Topic.CONNECTION, Actions.CHALLENGE_RESPONSE,  this.originalUrl ) );
        }
    }

    private void handleAuthResponse( Message message ) {
        if( message.action == Actions.ERROR ) {
            if( message.data[0].equals( Event.TOO_MANY_AUTH_ATTEMPTS.name() ) ) {
                this.tooManyAuthAttempts = true;
            } else {
                this.setState( ConnectionState.AWAITING_AUTHENTICATION );
            }

            if( this.loginCallback != null ) {
                this.loginCallback.loginFailed(Event.getEvent( message.data[ 0 ] ), message.data[ 1 ] );
            }
        }
        else if( message.action == Actions.ACK ) {
            this.setState( ConnectionState.OPEN );

            if( this.messageBuffer.length() > 0 ) {
                System.out.println( "Flushing initial buffer: " + this.messageBuffer.toString() );
                this.endpoint.send( this.messageBuffer.toString() );
                this.messageBuffer = new StringBuilder();
            }

            if( this.loginCallback != null ) {
                this.loginCallback.loginSuccess( new HashMap() );
            }
        }
    }

    private void setState( ConnectionState connectionState ) {
        this.connectionState = connectionState;

        if( connectionState == ConnectionState.AWAITING_CONNECTION && this.authParameters != null ) {
            this.sendAuthMessage();
        }

        Iterator listeners = this.connectStateListeners.iterator();
        while( listeners.hasNext() ) {
            ( (ConnectionChangeListener)listeners.next() ).connectionStateChanged( connectionState );
        }
    }

    private Endpoint createEndpoint(String url, Map options) throws Exception {
        Endpoint endpoint;
        System.out.println( options.get( "endpoint") + " " +  EndpointType.ENGINEIO.name() );
        if( options.get( "endpoint" ) == EndpointType.TCP.name() ) {
            endpoint = new EndpointTCP( url, options );
        } else if( options.get( "endpoint" ).equals( EndpointType.ENGINEIO.name() ) ) {
            endpoint = new EndpointEngineIO( url, options, this );
        } else {
            throw new Exception( "Unknown Endpoint" );
        }
        return endpoint;
    }
}