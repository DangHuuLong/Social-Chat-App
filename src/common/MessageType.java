
package common;

public enum MessageType {
	LOGIN       ((byte) 0),   
    REGISTER    ((byte) 1),  
    DM          ((byte) 2),   
    ACK         ((byte) 3), 
    ERROR       ((byte) 4),   
    HISTORY     ((byte) 5),  
    FILE_META   ((byte) 9),   
    FILE_CHUNK  ((byte)10),   
    AUDIO_META  ((byte)11),   
    AUDIO_CHUNK ((byte)12),  
    FILE_EVT    ((byte)13),   
    AUDIO_EVT   ((byte)14),  
    CALL_INVITE ((byte)20),  
    CALL_ACCEPT ((byte)21),
    CALL_REJECT ((byte)22),
    CALL_CANCEL ((byte)23),
    CALL_BUSY   ((byte)24),
    CALL_END    ((byte)25),
    CALL_OFFER  ((byte)26),   
    CALL_ANSWER ((byte)27),   
    CALL_ICE    ((byte)28),  
    CALL_OFFLINE((byte)29),   
    DOWNLOAD_FILE((byte)30),
    DELETE_MSG((byte)31),
    EDIT_MSG((byte)32),
    SEARCH((byte)33),
    SEARCH_HIT((byte)34),
    FILE_HISTORY((byte)35),     // ✅ Added for file history scrolling
    DELETE_FILE((byte)36),
    DELETE_AUDIO((byte)37),
    AUDIO_HISTORY((byte)38),
	DOWNLOAD_AUDIO((byte)39),
	SMART_REPLY((byte)40),
	CREATE_GROUP((byte)41),
	ADD_MEMBER((byte)42),
	REMOVE_MEMBER((byte)43),
	DELETE_GROUP((byte)44),
	LIST_MEMBERS((byte)45),
	GROUP_MSG((byte)46),
	GROUP_HISTORY((byte)47),
	GROUP_SYSTEM((byte)48),
	GROUP_LIST((byte)49);
    public final byte id;
    MessageType(byte id){ this.id = id; }

    public static MessageType from(byte b){
        for (var t : values()) if (t.id == b) return t;
        throw new IllegalArgumentException("Unknown MessageType id: " + b);
    }
}
