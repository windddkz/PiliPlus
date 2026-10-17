package com.example.piliplus.official;

import java.util.*;

/** A single authorized response's compatible video ladder; audio remains independently selected. */
final class OfficialVideoTracks {
    final List<OfficialStream> tracks;
    final OfficialStream origin;
    final boolean automatic;
    OfficialVideoTracks(OfficialStream origin,List<OfficialStream> tracks,boolean automatic) {
        if(origin==null || origin.videoCodec()<0 || tracks==null || tracks.isEmpty() || tracks.size()>20)
            throw new IllegalArgumentException("Invalid video ladder");
        Set<Integer> ids=new HashSet<>();boolean found=false;
        List<OfficialStream> copy=new ArrayList<>(tracks);
        for(OfficialStream track:copy) {
            if(track==null || !ids.add(track.id) || track.videoCodec()!=origin.videoCodec() || colorFamily(track.id)!=colorFamily(origin.id))
                throw new IllegalArgumentException("Incompatible video ladder");
            if(automatic && track.bandwidth<=0)throw new IllegalArgumentException("Automatic video requires bitrate metadata");
            if(track.id==origin.id) {
                if(!sameSource(track,origin))throw new IllegalArgumentException("Video ladder does not contain selected source");
                found=true;
            }
        }
        if(!found)throw new IllegalArgumentException("Selected quality absent from video ladder");
        copy.sort((a,b)->Integer.compare(b.id,a.id));
        this.tracks=Collections.unmodifiableList(copy);this.origin=origin;this.automatic=automatic;
    }
    static int colorFamily(int quality) {
        // Cross-SDR/HDR transitions need a new output surface and retain the full-open path.
        return quality==125 || quality==126 || quality==129 ? quality : 0;
    }
    static boolean sameSource(OfficialStream a,OfficialStream b) {
        return a.id==b.id && a.bandwidth==b.bandwidth && Objects.equals(a.codecs,b.codecs) && a.source.equals(b.source) && a.backups.equals(b.backups);
    }
    int min(){return tracks.get(tracks.size()-1).id;}
    int max(){return tracks.get(0).id;}
    OfficialStream get(int quality) {
        for(OfficialStream track:tracks)if(track.id==quality)return track;
        throw new IllegalArgumentException("Quality unavailable in current video ladder");
    }
    void validateRefresh(OfficialVideoTracks next) {
        // Controls may change the selected origin/mode; refresh must preserve the allowed ladder.
        if(next==null || tracks.size()!=next.tracks.size())
            throw new IllegalArgumentException("Refresh changed video policy");
        for(OfficialStream track:tracks) {
            OfficialStream replacement=next.get(track.id);
            if(!Objects.equals(track.codecs,replacement.codecs))throw new IllegalArgumentException("Refresh changed video codec");
        }
    }
}
