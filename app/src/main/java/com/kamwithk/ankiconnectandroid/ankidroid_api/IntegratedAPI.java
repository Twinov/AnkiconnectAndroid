package com.kamwithk.ankiconnectandroid.ankidroid_api;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseArray;
import android.widget.Toast;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.util.*;

import static com.ichi2.anki.api.AddContentApi.READ_WRITE_PERMISSION;

import com.kamwithk.ankiconnectandroid.request_parsers.MediaRequest;
import com.ichi2.anki.FlashCardsContract;
import com.ichi2.anki.api.AddContentApi;
import com.ichi2.anki.api.NoteInfo;
import com.kamwithk.ankiconnectandroid.request_parsers.Parser;

public class IntegratedAPI {
    private Context context;
    public final DeckAPI deckAPI;
    public final ModelAPI modelAPI;
    public final NoteAPI noteAPI;
    public final MediaAPI mediaAPI;
    private final AddContentApi api; // TODO: Combine all API classes???

    public IntegratedAPI(Context context) {
        this.context = context;

        deckAPI = new DeckAPI(context);
        modelAPI = new ModelAPI(context);
        noteAPI = new NoteAPI(context);
        mediaAPI = new MediaAPI(context);

        api = new AddContentApi(context);
    }

    public static void authenticate(Context context) {
        int permission = ContextCompat.checkSelfPermission(context, READ_WRITE_PERMISSION);

        if (permission != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions((Activity)context, new String[]{READ_WRITE_PERMISSION}, 0);
        }
    }

    //public File getExternalFilesDir() {
    //    return context.getExternalFilesDir(null);
    //}

    public void addSampleCard() {
        Map<String, String> data = new HashMap<>();
        data.put("Back", "sunrise");
        data.put("Front", "日の出");

        try {
            addNote(data, "Temporary", "Basic", null);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    public ArrayList<Boolean> canAddNotes(ArrayList<Parser.NoteFront> notesToTest) throws Exception {

        if (notesToTest.size() <= 0) {
            return new ArrayList<>();
        }
        String modelName = notesToTest.get(0).getModelName();

        // If all model names are the same, then we can run one call to api.findDuplicateNotes()
        // in order to speed up query times
        boolean sameModelName = true;
        for (Parser.NoteFront noteFront : notesToTest) {
            if (!modelName.equals(noteFront.getModelName())) {
                sameModelName = false;
                break;
            }
        }

        if (sameModelName) {
            Map<String, Long> modelNameToId = modelAPI.modelNamesAndIds(0);
            Long modelId = modelNameToId.get(modelName);
            if (modelId == null) { // i.e. not found
                // all false! (cannot add the note if there's no valid model to add it with)
                ArrayList<Boolean> allFalse = new ArrayList<>();
                for (int i = 0; i < notesToTest.size(); i++) {
                    allFalse.add(false);
                }
                return allFalse;
            }

            // Otherwise, we finally can use the internal API call
            List<String> keys = new ArrayList<>();
            for (Parser.NoteFront noteFront : notesToTest) {
                keys.add(noteFront.getFieldValue());
            }
            SparseArray<List<NoteInfo>> duplicateNotes =
                    api.findDuplicateNotes(modelId, keys);

            ArrayList<Boolean> noteDoesNotExist = new ArrayList<>();
            for (int i = 0; i < notesToTest.size(); i++) {
                noteDoesNotExist.add(duplicateNotes.get(i) == null);
            }
            return noteDoesNotExist;

        } else {
            // Use the old code instead for correctness, at the cost of performance.
            // TODO: We can probably use findDuplicateNotes here as well for 100% correctness
            //       with old AnkiDroid versions
            ArrayList<Boolean> noteDoesNotExist = new ArrayList<>();

            for (Parser.NoteFront noteFront : notesToTest) {
                // NOTE: This does not work if the field value has spaces or quotations unless
                // the rust backend is used!
                String escapedQuery = NoteAPI.escapeQueryStr(noteFront.getFieldName() + ":" + noteFront.getFieldValue());
                final Cursor cursor = context.getContentResolver().query(
                        FlashCardsContract.Note.CONTENT_URI,
                        null,
                        escapedQuery,
                        null,
                        null
                );

                noteDoesNotExist.add(cursor == null || !cursor.moveToFirst());
                if (cursor != null) {
                    cursor.close();
                }
            }

            return noteDoesNotExist;

        }

    }

    /**
     * Add flashcards to AnkiDroid through instant add API
     * @param data Map of (field name, field value) pairs
     * @return The id of the note added
     */
    public Long addNote(final Map<String, String> data, String deck_name, String model_name, Set<String> tags) throws Exception {
        Long deck_id = deckAPI.getDeckID(deck_name);
        Long model_id = modelAPI.getModelID(model_name, data.size());
        Long note_id = noteAPI.addNote(data, deck_id, model_id, tags);

        if (note_id != null) {
            new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(context, "Card added", Toast.LENGTH_SHORT).show());
            return note_id;
        } else {
            new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(context, "Failed to add card", Toast.LENGTH_SHORT).show());
            throw new Exception("Couldn't add note");
        }
    }

    /**
     * Adds the media to the collection, and updates noteValues
     *
     * @param noteValues Map from field name to field value
     * @param mediaRequests
     * @throws Exception
     */
    public void addMedia(Map<String, String> noteValues, List<MediaRequest> mediaRequests) throws Exception {

        // Store media and create a field: enclosed_filename map to avoid O(n^2) lookup later
        Map<String, ArrayList<String>> field_to_files = new HashMap<>();
        for (MediaRequest media: mediaRequests) {
            // mediaAPI.storeMediaFile() doesn't store as the passed in filename, need to use the returned one
            Optional<byte[]> data = media.getData();
            Optional<String> url = media.getUrl();
            String stored_filename;
            if (data.isPresent()) {
                stored_filename = mediaAPI.storeMediaFile(media.getFilename(), data.get());
            } else if (url.isPresent()) {
                stored_filename = mediaAPI.downloadAndStoreBinaryFile(media.getFilename(), url.get());
            } else {
                throw new Exception("You must provide a \"data\" or \"url\" field. Note that \"path\" is currently not supported on AnkiConnectAndroid.");
            }

            String enclosed_filename = "";
            switch (media.getMediaType()) {
                case AUDIO:
                case VIDEO:
                    enclosed_filename = "[sound:" + stored_filename + "]";
                    break;
                case PICTURE:
                    enclosed_filename = "<img src=\"" + stored_filename + "\">";
                    break;
            }

            for (String field: media.getFields()) {
                if (!field_to_files.containsKey(field)) {
                    field_to_files.put(field, new ArrayList<>());
                }
                field_to_files.get(field).add(enclosed_filename);
            }
        }

        for (String fieldName : noteValues.keySet()) {
            ArrayList<String> enclosed_media_filenames = field_to_files.get(fieldName);
            if (enclosed_media_filenames != null) {
                for (String enclosed_media_filename: enclosed_media_filenames) {
                    // noteValues[fieldName] += enclosed_media_filename
                    String fieldValue = noteValues.get(fieldName);
                    noteValues.put(fieldName, fieldValue + enclosed_media_filename);
                }
            }
        }
    }

    public void updateNoteFields(long note_id, Map<String, String> newFields, ArrayList<MediaRequest> mediaRequests) throws Exception {
        /*
         * updateNoteFields request looks like:
         * id: int,
         * fields: {
         *     field_name: string
         * },
         * audio | video | picture: [
         *     {
         *         data: base64 string,
         *         filename: string,
         *         fields: string[]
         *         + more fields that are currently unsupported
         *      }
         * ]
         *
         * Fields is an incomplete list of fields, and the Anki API expects the the passed in field
         * list to be complete. So, need to get the existing fields and only update them if present
         * in the request. Also need to reverse map each media file back to the field it will be
         * included in and append it enclosed in either <img> or [sound: ]
         */

        String[] modelFieldNames = modelAPI.modelFieldNames(noteAPI.getNoteModelId(note_id));
        String[] originalFields = noteAPI.getNoteFields(note_id);

        // updated fields
        HashMap<String, String> cardFields = new HashMap<>();

        // Get old fields and update values as needed
        for (int i = 0; i < modelFieldNames.length; i++) {
            String fieldName = modelFieldNames[i];

            String newValue = newFields.get(modelFieldNames[i]);
            if (newValue != null) {
                // Update field to new value
                cardFields.put(fieldName, newValue);
            } else {
                cardFields.put(fieldName, originalFields[i]);
            }
        }

        addMedia(cardFields, mediaRequests);
        noteAPI.updateNoteFields(note_id, cardFields);
    }

    public String storeMediaFile(BinaryFile binaryFile) throws IOException {
        return mediaAPI.storeMediaFile(binaryFile.getFilename(), binaryFile.getData());
    }

    public ArrayList<Long> guiBrowse(String query) {
        // https://github.com/ankidroid/Anki-Android/pull/11899
        Uri webpage = Uri.parse("anki://x-callback-url/browser?search=" + query);
        Intent webIntent = new Intent(Intent.ACTION_VIEW, webpage);
        webIntent.setPackage("com.ichi2.anki");
        // FLAG_ACTIVITY_NEW_TASK is needed in order to display the intent from a different app
        // FLAG_ACTIVITY_CLEAR_TOP and Intent.FLAG_ACTIVITY_TASK_ON_HOME is needed in order to not
        // cause a long chain of activities within Ankidroid
        // (i.e. browser <- word <- browser <- word <- browser <- word)
        // FLAG_ACTIVITY_CLEAR_TOP also allows the browser window to refresh with the new word
        // if AnkiDroid was already on the card browser activity.
        // see: https://stackoverflow.com/a/23874622
        webIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_TASK_ON_HOME);
        context.startActivity(webIntent);

        // The result doesn't seem to be used by Yomichan at all, so it can be safely ignored.
        // If we want to get the results, calling the findNotes() method will likely cause
        // unwanted delay.
        return new ArrayList<>();
    }
}

