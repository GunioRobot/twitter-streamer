package com.unclehulka.twitter.streamer;

import org.apache.http.client.ClientProtocolException;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.HttpResponse;
import org.codehaus.jackson.*;
import org.codehaus.jackson.annotate.JsonProperty;
import org.codehaus.jackson.annotate.JsonAnySetter;
import org.codehaus.jackson.map.annotate.JsonDeserialize;
import org.codehaus.jackson.map.JsonDeserializer;
import org.codehaus.jackson.map.DeserializationContext;
import org.codehaus.jackson.map.ObjectMapper;

import java.io.*;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Date;
import java.text.SimpleDateFormat;
import java.text.ParseException;
import java.util.concurrent.*;

/**
 * A very simple implementation of a Twitter client utilizing the new Twitter stream APIs:
 *
 *     http://apiwiki.twitter.com/Streaming-API-Documentation
 *
 * The client connects to the stream API and parses the statuses returned, printing some simple
 * diagnostic information on each status update to the screen.
 *
 * This client makes use of Apache HttpComponents HttpClient and Jackson, which are available
 * at the URLs below.
 *
 *     http://hc.apache.org/httpcomponents-client/
 *     http://jackson.codehaus.org/
 *
 * @author Ryan Kennedy <ryan.kennedy@yahoo.com>
 */
public class TwitterStreamer {
    public static void main(String[] args) {
        stream(args[0], args[1]);
    }

    private static void stream(String username, String password) {
        // Set up an HttpClient instance.
        DefaultHttpClient client = new DefaultHttpClient();

        // Set up the credentials. Twitter's stream APIs require username/password.
        client.getCredentialsProvider().setCredentials(
                new AuthScope("stream.twitter.com", 80),
                new UsernamePasswordCredentials(username, password));

        // Call the "sample" stream, the heavier hoses require approval from Twitter.
        HttpGet get = new HttpGet("http://stream.twitter.com/1/statuses/sample.json");
        try {
            // Execute the request.
            HttpResponse response = client.execute(get);

            // Create a new TwitterStream using the InputStream from the HTTP connection.
            TwitterStream stream = new TwitterStream(response.getEntity().getContent());

            // Iterate over the TwitterStatus objects parsed from the stream.
            for(TwitterStatus status : stream) {
                // Dump out some simple information so we can see the tweets being fetched and parsed.
                if(status.user != null) {
                    System.out.println(String.format("Tweet from %s (%s)", status.user.name, status.user.screenName));
                    System.out.println(String.format("  %s", status.text));
                    System.out.println("--------------------------------------------------");
                }
                else if(status.delete != null) {
                    System.out.println(String.format("Tweet %s deleted by %s", status.delete.status.id, status.delete.status.user_id));
                    System.out.println("--------------------------------------------------");
                }
                else {
                    // Catch all for objects we don't fully understand. Twitter is good at adding new types without
                    // changing the API version.
                    System.out.println("Not sure what this object is...");
                    JsonGenerator generator = new JsonFactory().createJsonGenerator(System.out, JsonEncoding.UTF8);
                    generator.setCodec(new ObjectMapper());
                    generator.writeObject(status);
                    System.exit(1);
                }
            }

            // The stream iterator
            System.out.println("Disconnected");
        }
        catch(IOException e) {
            // Handle errors.
            System.err.println("Error processing Twitter stream: " + e.toString());
            e.printStackTrace(System.err);
        }
    }

    /**
     * An Iterable TwitterStream, suitable for use in Java for-each loops.
     */
    private static class TwitterStream implements Iterable<TwitterStatus> {
        private TwitterStatusIterator iterator;

        public TwitterStream(InputStream stream) throws IOException {
            iterator = new TwitterStatusIterator(new JsonFactory().createJsonParser(stream));
        }

        public Iterator<TwitterStatus> iterator() {
            return iterator;
        }
    }

    /**
     * A simple TwitterStatus iterator. Access to the iterator is synchronized to make sure two threads
     * don't bicker too much over the InputStream.
     */
    private static class TwitterStatusIterator implements Iterator<TwitterStatus> {
        private BlockingQueue<JsonNode> statusQueue;
        private ObjectMapper mapper;
        private boolean connected;

        public TwitterStatusIterator(final JsonParser parser) {
            // Create a queue to hold the JSON objects parsed from the stream.
            statusQueue = new ArrayBlockingQueue<JsonNode>(50, false);

            mapper = new ObjectMapper();

            // Whether or not the connection to Twitter is still open.
            connected = true;

            // Parse the JSON object skeleton in a separate thread to make sure we're keeping up with
            // the stream from Twitter. Not keeping up will cause Twitter to disconnect from their end.
            Thread reader = new Thread(new Runnable() {
                public void run() {
                    while(true) {
                        try {
                            // Try putting the JSON object in the queue if it will fit.
                            if(!statusQueue.offer(mapper.readTree(parser))) {
                                System.err.println("Dropped status");
                            }
                        }
                        catch(JsonProcessingException e) {
                            // A badly formatted JSON object, perhaps. Skip it.
                            System.err.println("Failed to parse JSON object: " + e.toString());
                            e.printStackTrace(System.err);
                        }
                        catch(IOException e) {
                            // An IOException may indicate that Twitter has closed the stream on their end.
                            System.err.println("Failed to parse JSON object: " + e.toString());
                            e.printStackTrace(System.err);
                            connected = false;
                            return;
                        }
                    }
                }
            });
            reader.start();
        }

        public synchronized boolean hasNext() {
            return connected;
        }

        public synchronized TwitterStatus next() {
            // See if there's a next to return.
            while(hasNext()) {
                // Try to parse and return a status object. Discard and log bogus JSON objects.
                try {
                    return mapper.treeToValue(statusQueue.take(), TwitterStatus.class);
                }
                catch(IOException e) {
                    System.err.println("Failed to successfully parse JsonNode: " + e.toString());
                    e.printStackTrace(System.err);
                }
                catch(InterruptedException e) {
                    System.err.println("Interrupted while waiting to process queue");
                    e.printStackTrace(System.err);
                    connected = false;
                }
            }

            throw new NoSuchElementException("No more statuses to return");
        }

        public void remove() {
            // Remove isn't supported by this Iterator.
            throw new UnsupportedOperationException("TwitterStatusIterator doesn't support removal.");
        }
    }

    public static class TwitterStatus {
        public Boolean truncated;

        public String text;

        public Boolean favorited;

        @JsonProperty(value = "in_reply_to_user_id")
        public String inReplyToUserId;

        @JsonProperty(value = "in_reply_to_status_id")
        public Long inReplyToStatusId;

        @JsonProperty(value = "in_reply_to_screen_name")
        public String inReplyToScreenName;

        public String source;

        public String contributors;

        public String geo;

        public TwitterUser user;

        public Long id;

        @JsonProperty(value = "created_at")
        @JsonDeserialize(using = TwitterDateDeserializer.class)
        public Date createdAt;

        public TwitterDelete delete;

        @JsonProperty(value = "retweeted_status")
        public TwitterStatus retweet;

        @JsonAnySetter
        public void setProperty(String key, Object value) {
            System.out.println(String.format("Missing @JsonProperty in TwitterStatus for %s => %s", key, value.toString()));
            System.exit(1);
        }
    }

    public static class TwitterUser {
        @JsonProperty(value = "contributors_enabled")
        public Boolean contributorsEnabled;

        @JsonProperty(value = "profile_sidebar_fill_color")
        public String profileSidebarFillColor;

        @JsonProperty(value = "screen_name")
        public String screenName;

        @JsonProperty(value = "lang")
        public String language;

        @JsonProperty(value = "profile_background_tile")
        public Boolean profileBackgroundTile;

        public String location;

        public Boolean following;

        @JsonProperty(value = "profile_sidebar_border_color")
        public String profileSidebarBorderColor;

        public Boolean verified;

        @JsonProperty(value = "followers_count")
        public Long followersCount;

        public String description;

        @JsonProperty(value = "friends_count")
        public Long friendsCount;

        public Boolean notifications;

        @JsonProperty(value = "profile_background_color")
        public String profileBackgroundColor;

        public String url;

        @JsonProperty(value = "favourites_count")
        public Long favoritesCount;

        @JsonProperty(value = "profile_text_color")
        public String profileTextColor;

        @JsonProperty(value = "protected")
        public Boolean isProtected;

        @JsonProperty(value = "time_zone")
        public String timeZone;

        public String name;

        @JsonProperty(value = "statuses_count")
        public Long statusesCount;

        @JsonProperty(value = "profile_link_color")
        public String profileLinkColor;

        @JsonProperty(value = "profile_image_url")
        public String profileImageUrl;

        public String id;

        @JsonProperty(value = "geo_enabled")
        public Boolean geoEnabled;

        @JsonProperty(value = "profile_background_image_url")
        public String profileBackgroundImageUrl;

        @JsonProperty(value = "utc_offset")
        public Long utcOffset;

        @JsonProperty(value = "created_at")
        @JsonDeserialize(using = TwitterDateDeserializer.class)
        public Date createdAt;

        @JsonAnySetter
        public void setProperty(String key, Object value) {
            System.out.println(String.format("Missing @JsonProperty in TwitterStatus for %s => %s", key, value.toString()));
            System.exit(1);
        }
    }

    public static class TwitterDelete {
        public TwitterDeletedStatus status;

        @JsonAnySetter
        public void setProperty(String key, Object value) {
            System.out.println(String.format("Missing @JsonProperty in TwitterDelete for %s => %s", key, value.toString()));
            System.exit(1);
        }
    }

    public static class TwitterDeletedStatus {
        public String id;
        public String user_id;

        @JsonAnySetter
        public void setProperty(String key, Object value) {
            System.out.println(String.format("Missing @JsonProperty in TwitterDeletedStatus for %s => %s", key, value.toString()));
            System.exit(1);
        }
    }

    public static class TwitterDateDeserializer extends JsonDeserializer<Date> {
        public Date deserialize(JsonParser jp, DeserializationContext ctxt) throws IOException {
            try {
                SimpleDateFormat sdf = new SimpleDateFormat("EEE MMM dd HH:mm:ss Z yyyy");
                return sdf.parse(jp.getText());
            }
            catch(ParseException e) {
                throw new IOException("Error parsing Twitter date format: " + e.toString());
            }
        }
    }
}