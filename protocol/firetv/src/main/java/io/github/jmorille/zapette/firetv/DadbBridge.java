package io.github.jmorille.zapette.firetv;

import java.io.Closeable;
import java.io.IOException;
import java.util.Set;

import dadb.AdbConnection;
import dadb.AdbMessage;
import dadb.AdbReader;
import dadb.AdbStream;
import dadb.AdbWriter;
import dadb.Constants;
import okio.Sink;
import okio.Source;

/**
 * Pont Java vers les classes de dadb 2.0.0 déclarées {@code internal} en Kotlin (donc
 * publiques en bytecode, mais inaccessibles depuis Kotlin) : lecteur/écrivain de messages
 * et constructeur d'{@link AdbConnection}. Permet de confier à dadb un flux déjà chiffré
 * par TLS, ce que la bibliothèque ne sait pas établir elle-même.
 *
 * Dépend de détails non garantis de dadb : couvert par les tests JVM, à revérifier à
 * chaque montée de version de dadb.
 */
final class DadbBridge {
    private DadbBridge() {
    }

    /** Charge utile CNXN de dadb (bannière « host:: » et fonctionnalités annoncées). */
    static byte[] connectPayload() {
        return Constants.INSTANCE.getCONNECT_PAYLOAD();
    }

    static int connectVersion() {
        return Constants.CONNECT_VERSION;
    }

    static int connectMaxData() {
        return Constants.CONNECT_MAXDATA;
    }

    /** Connexion ADB au-dessus d'un flux établi : lit le CNXN de l'appareil puis rend la main à dadb. */
    static Connection open(Source source, Sink sink, Closeable closeable) throws IOException {
        AdbReader reader = new AdbReader(source);
        AdbWriter writer = new AdbWriter(sink);
        AdbMessage connect = reader.readMessage();
        if (connect.getCommand() != Constants.CMD_CNXN) {
            throw new IOException("Réponse ADB inattendue après TLS (0x" + Integer.toHexString(connect.getCommand()) + ")");
        }
        String banner = new String(connect.getPayload(), java.nio.charset.StandardCharsets.UTF_8);
        Set<String> features = AdbTlsConnection.parseFeatures(banner);
        return new Connection(new AdbConnection(reader, writer, closeable, features, connect.getArg0(), connect.getArg1()));
    }

    /** Vue publique (côté Kotlin) d'une {@link AdbConnection}. */
    static final class Connection implements AutoCloseable {
        private final AdbConnection connection;

        Connection(AdbConnection connection) {
            this.connection = connection;
        }

        AdbStream open(String destination) throws dadb.AdbException {
            return connection.open(destination);
        }

        boolean supportsFeature(String feature) {
            return connection.supportsFeature(feature);
        }

        @Override
        public void close() {
            connection.close();
        }
    }
}
