package de.danoeh.antennapod.net.ai.service.ad.vosk;

import java.util.Objects;

/**
 * Represents a Vosk transcription model.
 */
public class VoskModel {
    private final String id;
    private final String name;
    private final String language;
    private final String url;
    private final long size;

    public VoskModel(String id, String name, String language, String url, long size) {
        this.id = id;
        this.name = name;
        this.language = language;
        this.url = url;
        this.size = size;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getLanguage() {
        return language;
    }

    public String getUrl() {
        return url;
    }

    public long getSize() {
        return size;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        VoskModel voskModel = (VoskModel) o;
        return Objects.equals(id, voskModel.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return name + " (" + language + ")";
    }
}
