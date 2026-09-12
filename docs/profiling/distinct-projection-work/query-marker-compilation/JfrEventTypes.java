import jdk.jfr.*;
import jdk.jfr.consumer.*;
import java.nio.file.*;
import java.util.*;

public final class JfrEventTypes {
    public static void main(String[] args) throws Exception {
        List<Object> values=new ArrayList<>();
        try(RecordingFile file=new RecordingFile(Path.of(args[0]))) {
            for(EventType event:file.readEventTypes()) {
                Map<String,Object> row=new LinkedHashMap<>();
                row.put("id",event.getId());row.put("name",event.getName());
                Map<String,Object> defaults=new LinkedHashMap<>();
                for(SettingDescriptor setting:event.getSettingDescriptors())defaults.put(setting.getName(),setting.getDefaultValue());
                row.put("settingDefaults",defaults);values.add(row);
            }
        }
        Files.writeString(Path.of(args[1]),ExportOriginalCatalog.json(values)+"\n");
    }
}
