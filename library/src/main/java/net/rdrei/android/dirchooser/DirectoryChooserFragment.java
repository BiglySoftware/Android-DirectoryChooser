package net.rdrei.android.dirchooser;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DialogFragment;
import android.app.Fragment;
import android.content.DialogInterface;
import android.os.Bundle;
import android.os.Environment;
import android.os.FileObserver;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.*;
import android.widget.*;

import androidx.annotation.*;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import net.rdrei.android.dirchooser.ThreadUtils.RunnableWithObject;

import java.io.File;
import java.util.*;

/**
 * Activities that contain this fragment must implement the
 * {@link OnFragmentInteractionListener} interface
 * to handle interaction events.
 * Use the {@link DirectoryChooserFragment#newInstance} factory method to
 * create an instance of this fragment.
 */
@SuppressWarnings({
    "ConfusingElseBranch",
    "unused"
})
public class DirectoryChooserFragment extends DialogFragment {
    public static final String KEY_CURRENT_DIRECTORY = "CURRENT_DIRECTORY";
    private static final String ARG_CONFIG = "CONFIG";
    private static final String TAG = DirectoryChooserFragment.class.getSimpleName();

    public static final int FILE_OBSERVER_MASK =
        FileObserver.CREATE | FileObserver.DELETE | FileObserver.MOVED_FROM
            | FileObserver.MOVED_TO;

    String mNewDirectoryName;
    private String mInitialDirectory;

    OnFragmentInteractionListener mListener = null;

    private Button mBtnConfirm;
    Button mBtnCancel;
    private ImageButton mBtnNavUp;
    private ImageButton mBtnCreateFolder;
    private TextView mTxtvSelectedFolder;
    private ListView mListDirectories;

    private ArrayAdapter<String> mListDirectoriesAdapter;
    private final List<String> mFilenames = new ArrayList<>();
    /**
     * The directory that is currently being shown.
     */
    File mSelectedDir;
    File[] mFilesInDir;
    FileObserver mFileObserver;
    private DirectoryChooserConfig mConfig;

    private boolean changingDirectory;

    public DirectoryChooserFragment() {
        // Required empty public constructor
    }

    /**
     * To create the config, make use of the provided
     * {@link DirectoryChooserConfig#builder()}.
     *
     * @return A new instance of DirectoryChooserFragment.
     */
    public static DirectoryChooserFragment newInstance(@NonNull final DirectoryChooserConfig config) {
        final DirectoryChooserFragment fragment = new DirectoryChooserFragment();
        final Bundle args = new Bundle();
        args.putParcelable(ARG_CONFIG, config);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onSaveInstanceState(@NonNull final Bundle outState) {
        super.onSaveInstanceState(outState);

        if (mSelectedDir != null) {
            outState.putString(KEY_CURRENT_DIRECTORY, mSelectedDir.getAbsolutePath());
        }
    }

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (getArguments() == null) {
            throw new IllegalArgumentException(
                    "You must create DirectoryChooserFragment via newInstance().");
        }
        mConfig = getArguments().getParcelable(ARG_CONFIG);

        if (mConfig == null) {
            throw new NullPointerException("No ARG_CONFIG provided for DirectoryChooserFragment " +
                    "creation.");
        }

        mNewDirectoryName = mConfig.newDirectoryName();
        mInitialDirectory = mConfig.initialDirectory();

        if (savedInstanceState != null) {
            mInitialDirectory = savedInstanceState.getString(KEY_CURRENT_DIRECTORY);
        }

        if (getShowsDialog()) {
            setStyle(DialogFragment.STYLE_NO_TITLE, 0);
        } else {
            setHasOptionsMenu(true);
        }

        if (!mConfig.allowNewDirectoryNameModification() && TextUtils.isEmpty(mNewDirectoryName)) {
            throw new IllegalArgumentException("New directory name must have a strictly positive " +
                    "length (not zero) when user is not allowed to modify it.");
        }
    }

    @Override
    public View onCreateView(final LayoutInflater inflater, final ViewGroup container,
            final Bundle savedInstanceState) {

        assert getActivity() != null;
        final View view = inflater.inflate(R.layout.directory_chooser, container, false);

        mBtnConfirm = (Button) view.findViewById(R.id.btnConfirm);
        mBtnCancel = (Button) view.findViewById(R.id.btnCancel);
        mBtnNavUp = (ImageButton) view.findViewById(R.id.btnNavUp);
        mBtnCreateFolder = (ImageButton) view.findViewById(R.id.btnCreateFolder);
        mTxtvSelectedFolder = (TextView) view.findViewById(R.id.txtvSelectedFolder);
        mListDirectories = (ListView) view.findViewById(R.id.directoryList);

        mBtnConfirm.setOnClickListener(v -> isValidFile(mSelectedDir, valid -> {
            if (valid) {
                returnSelectedFolder();
            }
        }));

        mBtnCancel.setOnClickListener(v -> {
            if (mListener == null) {
                return;
            }
            mListener.onCancelChooser();
        });

        mListDirectories.setOnItemClickListener(
            (parent, v, position, id) -> {
                debug("Selected index: %d", position);
                if (mFilesInDir != null && position >= 0
                        && position < mFilesInDir.length) {
                    changeDirectory(mFilesInDir[position]);
                }
            });
        
        
        mListDirectories.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                isValidFile(mFilesInDir[position], valid -> mBtnCancel.setEnabled(valid));
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {

            }
        });

        mBtnNavUp.setOnClickListener(v -> {
            final File parent;
            if (mSelectedDir != null
                    && (parent = mSelectedDir.getParentFile()) != null) {
                changeDirectory(parent);
            }
        });

        mBtnCreateFolder.setOnClickListener(v -> openNewFolderDialog());

// I don't understand why one would hide the create folder button when !getShowsDialog
//        if (!getShowsDialog()) {
//            mBtnCreateFolder.setVisibility(View.GONE);
//        }

        mListDirectoriesAdapter = new ArrayAdapter<>(getActivity(),
                android.R.layout.simple_list_item_1, mFilenames);
        mListDirectories.setAdapter(mListDirectoriesAdapter);

        ThreadUtils.runOffUIThread(() -> {
            final File initialDir;
            if (!TextUtils.isEmpty(mInitialDirectory) && isValidFile(new File(mInitialDirectory))) {
                initialDir = new File(mInitialDirectory);
            } else {
                initialDir = Environment.getExternalStorageDirectory();
            }

            changeDirectory(initialDir);
        }, "initialDir");

        return view;
    }

    @Override
    public void onAttach(final Activity activity) {
        super.onAttach(activity);

        if (activity instanceof OnFragmentInteractionListener) {
            mListener = (OnFragmentInteractionListener) activity;
        } else {
            Fragment owner = getTargetFragment();
            if (owner instanceof OnFragmentInteractionListener) {
                mListener = (OnFragmentInteractionListener) owner;
            }
        }
    }

    @Override
    public void onDetach() {
        super.onDetach();
        mListener = null;
    }

    @Override
    public void onPause() {
        super.onPause();
        if (mFileObserver != null) {
            mFileObserver.stopWatching();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mFileObserver != null) {
            mFileObserver.startWatching();
        }
    }

    @Override
    public void onCreateOptionsMenu(final Menu menu, final MenuInflater inflater) {
        inflater.inflate(R.menu.directory_chooser, menu);

        final MenuItem menuItem = menu.findItem(R.id.new_folder_item);

        if (menuItem == null) {
            return;
        }

        menuItem.setVisible(false);
        if (changingDirectory) {
            return;
        }
        isValidFile(mSelectedDir, valid -> menuItem.setVisible(valid && mNewDirectoryName != null));
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        final int itemId = item.getItemId();

        if (itemId == R.id.new_folder_item) {
            openNewFolderDialog();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    /**
     * Shows a confirmation dialog that asks the user if he wants to create a
     * new folder. User can modify provided name, if it was not disallowed.
     */
    private void openNewFolderDialog() {
        @SuppressLint("InflateParams")
        final View dialogView = getActivity().getLayoutInflater().inflate(
                R.layout.dialog_new_folder, null);
        final TextView msgView = (TextView) dialogView.findViewById(R.id.msgText);
        final EditText editText = (EditText) dialogView.findViewById(R.id.editText);
        editText.setText(mNewDirectoryName);
        msgView.setText(getString(R.string.create_folder_msg, mNewDirectoryName));

        final AlertDialog alertDialog = new MaterialAlertDialogBuilder(getActivity())
                .setTitle(R.string.create_folder_label)
                .setView(dialogView)
                .setNegativeButton(R.string.cancel_label, (dialog, which) -> dialog.dismiss())
                .setPositiveButton(R.string.confirm_label, (dialog, which) -> {
                    dialog.dismiss();
                    mNewDirectoryName = editText.getText().toString();
                    ThreadUtils.runOffUIThread(() -> {
                        final int msg = createFolder();
                        ThreadUtils.runOnUIThread(() -> 
                            Toast.makeText(getActivity(), msg, Toast.LENGTH_SHORT).show());
                    }, "createFolder");
                })
                .show();

        alertDialog.getButton(DialogInterface.BUTTON_POSITIVE).setEnabled(editText.getText().length() != 0);

        editText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(final CharSequence charSequence, final int i, final int i2, final int i3) {

            }

            @Override
            public void onTextChanged(final CharSequence charSequence, final int i, final int i2, final int i3) {
                final boolean textNotEmpty = charSequence.length() != 0;
                alertDialog.getButton(DialogInterface.BUTTON_POSITIVE).setEnabled(textNotEmpty);
                msgView.setText(getString(R.string.create_folder_msg, charSequence.toString()));
            }

            @Override
            public void afterTextChanged(final Editable editable) {

            }
        });

        editText.setVisibility(mConfig.allowNewDirectoryNameModification()
                ? View.VISIBLE : View.GONE);
    }

    static void debug(final String message, final Object... args) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, String.format(message, args));
        }
    }

    /**
     * Change the directory that is currently being displayed.
     *
     * @param dir The file the activity should switch to. This File must be
     *            non-null and a directory, otherwise the displayed directory
     *            will not be changed
     */
    void changeDirectory(final File dir) {
        changingDirectory = true;
        ThreadUtils.runOnUIThread(() -> {
            disableButtonState();
            ThreadUtils.runOffUIThread(() -> changeDirectoryOffUIThread(dir), 
                "changeDirectory");
        });
    }

    @WorkerThread
    private void changeDirectoryOffUIThread(File dir) {
        if (dir == null) {
            debug("Could not change folder: dir was null");
        } else if (!dir.isDirectory()) {
            debug("Could not change folder: dir is no directory");
        } else {
            File[] contents = dir.listFiles();
            if (contents != null) {
                synchronized (DirectoryChooserFragment.this) {
                    int numDirectories = 0;
                    for (final File f : contents) {
                        if (f.isDirectory()) {
                            numDirectories++;
                        }
                    }
                    final File[] filesInDir = new File[numDirectories];
                    List<String> filenames = new ArrayList<>();
                    for (int i = 0, counter = 0; i < numDirectories; counter++) {
                        if (contents[counter].isDirectory()) {
                            filesInDir[i] = contents[counter];
                            filenames.add(contents[counter].getName());
                            i++;
                        }
                    }
                    Arrays.sort(filesInDir);
                    Collections.sort(filenames);
                    String absolutePath = dir.getAbsolutePath();
                    if (!dir.equals(mSelectedDir)) {
                        if (mFileObserver != null) {
                            mFileObserver.stopWatching();
                        }
                        mFileObserver = createFileObserver(absolutePath);
                        mFileObserver.startWatching();
                    }

                    Activity activity = getActivity();
                    if (activity == null || activity.isFinishing()) {
                        return;
                    }
                    activity.runOnUiThread(() -> {
                        Activity curActivity = getActivity();
                        if (curActivity == null || curActivity.isFinishing()) {
                            return;
                        }

                        mFilesInDir = filesInDir;
                        mFilenames.clear();
                        mFilenames.addAll(filenames);
                        mSelectedDir = dir;
                        mTxtvSelectedFolder.setText(absolutePath);
                        mListDirectoriesAdapter.notifyDataSetChanged();
                        debug("Changed directory to %s", absolutePath);
                    });
                }
            } else {
                debug("Could not change folder: contents of dir were null");
            }
        }
        refreshButtonState(valid -> {
            changingDirectory = false;
            Activity activity = getActivity();
            if (activity == null || activity.isFinishing()) {
                return;
            }
            mBtnConfirm.setEnabled(valid &&
                (mConfig.allowReadOnlyDirectory() || mSelectedDir.canWrite()));
            mBtnCreateFolder.setEnabled(true);
            mBtnCreateFolder.setVisibility(
                valid && mSelectedDir.canWrite() ? View.VISIBLE : View.GONE);
            activity.invalidateOptionsMenu();
        });
    }

    /**
     * Changes the state of the buttons depending on the currently selected file
     * or folder.
     */
    @WorkerThread
    private void refreshButtonState(@UiThread RunnableWithObject<Boolean> runOnComplete) {
        if (mSelectedDir == null) {
            ThreadUtils.runOnUIThread(() -> runOnComplete.run(false));
            return;
        }
        isValidFile(mSelectedDir, runOnComplete);
    }
    
    @UiThread
    private void disableButtonState() {
        mBtnConfirm.setEnabled(false);
        mBtnCreateFolder.setEnabled(false);
    }

    /**
     * Refresh the contents of the directory that is currently shown.
     */
    void refreshDirectory() {
        if (mSelectedDir != null) {
            changeDirectory(mSelectedDir);
        }
    }

    /**
     * Sets up a FileObserver to watch the current directory.
     */
    @WorkerThread
    private FileObserver createFileObserver(final String path) {
        return new FileObserver(path, FILE_OBSERVER_MASK) {

            @Override
            public void onEvent(final int event, final String path) {
                if ((event & FILE_OBSERVER_MASK) == 0) {
                    debug("FileObserver received ignored event %d for %s", event, path);
                    return;
                }
                debug("FileObserver received event %d", event);
                refreshDirectory();
            }
        };
    }

    /**
     * Returns the selected folder as a result to the activity the fragment's attached to. The
     * selected folder can also be null.
     */
    void returnSelectedFolder() {
        if (mListener == null) {
            return;
        }
        if (mSelectedDir != null) {
            debug("Returning %s as result", mSelectedDir.getAbsolutePath());
            mListener.onSelectDirectory(mSelectedDir.getAbsolutePath());
        } else {
            mListener.onCancelChooser();
        }

    }

    /**
     * Creates a new folder in the current directory with the name
     * CREATE_DIRECTORY_NAME.
     */
    int createFolder() {
        if (mNewDirectoryName != null && mSelectedDir != null
                && mSelectedDir.canWrite()) {
            final File newDir = new File(mSelectedDir, mNewDirectoryName);
            if (newDir.exists()) {
                return R.string.create_folder_error_already_exists;
            } else {
                final boolean result = newDir.mkdir();
                if (result) {
                    return R.string.create_folder_success;
                } else {
                    return R.string.create_folder_error;
                }
            }
        } else if (mSelectedDir != null && !mSelectedDir.canWrite()) {
            return R.string.create_folder_error_no_write_access;
        } else {
            return R.string.create_folder_error;
        }
    }

    /**
     * Moves off UI Thread, checks if file is valid, and reports results
     */
    @AnyThread
    static void isValidFile(final File file, @NonNull final RunnableWithObject<Boolean> result) {
        ThreadUtils.runOffUIThread(() -> {
            boolean isValidFile = isValidFile(file);
            ThreadUtils.runOnUIThread(result, isValidFile);
        }, "isValidFile");
    }


    /**
     * Returns true if the selected file or directory would be valid selection.
     */
    @WorkerThread
    static boolean isValidFile(final File file) {
        return (file != null && file.isDirectory() && file.canRead());
    }

    @Nullable
    public OnFragmentInteractionListener getDirectoryChooserListener() {
        return mListener;
    }

    public void setDirectoryChooserListener(@Nullable final OnFragmentInteractionListener listener) {
        mListener = listener;
    }

    /**
     * This interface must be implemented by activities that contain this
     * fragment to allow an interaction in this fragment to be communicated
     * to the activity and potentially other fragments contained in that
     * activity.
     * <p/>
     * See the Android Training lesson <a href=
     * "http://developer.android.com/training/basics/fragments/communicating.html"
     * >Communicating with Other Fragments</a> for more information.
     */
    public interface OnFragmentInteractionListener {
        /**
         * Triggered when the user successfully selected their destination directory.
         */
        void onSelectDirectory(@NonNull String path);

        /**
         * Advices the activity to remove the current fragment.
         */
        void onCancelChooser();
    }

}
