package de.westnordost.streetcomplete.oauth;

import android.app.Activity;
import android.app.DialogFragment;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Browser;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.UiThread;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Toast;

import java.util.Arrays;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Provider;

import de.westnordost.osmapi.OsmConnection;
import de.westnordost.osmapi.user.Permission;
import de.westnordost.osmapi.user.PermissionsDao;
import de.westnordost.osmapi.user.UserDao;
import de.westnordost.streetcomplete.Injector;
import de.westnordost.streetcomplete.Prefs;
import de.westnordost.streetcomplete.R;
import de.westnordost.streetcomplete.data.OsmModule;
import de.westnordost.streetcomplete.util.InlineAsyncTask;
import oauth.signpost.OAuthConsumer;
import oauth.signpost.OAuthProvider;

/** Manages authorization with OAuthPrefs. You need to pass through the call to onResume from Activity */
public class OsmOAuthFragment extends DialogFragment
{
	public static final String TAG = "OsmOAuthFragment";

	// for loading and saving from bundle
	private static final String	CONSUMER = "consumer", STATE = "state";

	public static final List<String> REQUIRED_PERMISSIONS = Arrays.asList(
			Permission.READ_PREFERENCES_AND_USER_DETAILS,
			Permission.MODIFY_MAP,
			Permission.WRITE_NOTES);

	private static final String
			CALLBACK_SCHEME = "oauth",
			CALLBACK_HOST = "streetcomplete",
			CALLBACK_URL = CALLBACK_SCHEME + "://" + CALLBACK_HOST;

	@Inject SharedPreferences prefs;
	@Inject OAuthPrefs oAuth;
	@Inject OsmConnection globalOsmConnectionSingleton;
	@Inject Provider<OAuthConsumer> consumerProvider;
	@Inject OAuthProvider provider;

	// must use an own connection here and not the normal singleton because while the auth process
	// is not finished, the new authorized consumer is not applied to the global singleton yet
	private OsmConnection osmConnection;

	private OAuthConsumer consumer;

	private Listener listener;
	public interface Listener
	{
		void onOAuthAuthorized();
	}

	private String verifier;

	private State state;
	private enum State
	{
		INITIAL,
		RETRIEVING_REQUEST_TOKEN,
		AUTHENTICATING_IN_BROWSER,
		RETRIEVING_ACCESS_TOKEN,
		POST_AUTHORIZATION
	}

	@Override public void onCreate(@Nullable Bundle inState)
	{
		super.onCreate(inState);
		Injector.instance.getApplicationComponent().inject(this);

		if(inState != null)
		{
			consumer = (OAuthConsumer) inState.getSerializable(CONSUMER);
			state = State.valueOf(inState.getString(STATE));
		}
		else
		{
			consumer = consumerProvider.get();
			state = State.INITIAL;
		}
		osmConnection = OsmModule.osmConnection(consumer);
	}

	@Override public View onCreateView(LayoutInflater inflater, ViewGroup container,
									   Bundle savedInstanceState)
	{

		View view = inflater.inflate(R.layout.oauth_dialog_fragment, container, false);

		getDialog().getWindow().requestFeature(Window.FEATURE_NO_TITLE);

		return view;
	}

	@Override public void onAttach(Activity activity)
	{
		super.onAttach(activity);
		listener = (Listener) getActivity();
	}

	@Override public void onResume()
	{
		super.onResume();
		continueAuthentication();
	}

	@Override public void onCancel(DialogInterface dialog)
	{
		super.onCancel(dialog);
		Toast.makeText(getActivity(), R.string.oauth_cancelled, Toast.LENGTH_SHORT).show();
		onAuthorizationFailed();
	}

	@Override public void onSaveInstanceState(@NonNull Bundle outState)
	{
		outState.putSerializable(CONSUMER, consumer);
		outState.putString(STATE, state.toString());
		super.onSaveInstanceState(outState);
	}

	public void onNewIntent(Intent intent)
	{
		Uri uri = intent.getData();
		if (uri != null && uri.getScheme().equals(CALLBACK_SCHEME) && uri.getHost().equals(CALLBACK_HOST))
		{
			verifier = uri.getQueryParameter("oauth_verifier");
		}
		else
		{
			verifier = null;
		}
	}

	/* ------------------------------------------------------------------------------------------ */

	private void continueAuthentication()
	{
		if(state == State.INITIAL)
		{
			state = State.RETRIEVING_REQUEST_TOKEN;
			new RetrieveRequestTokenTask().execute();
		}
		else if(state == State.AUTHENTICATING_IN_BROWSER)
		{
			state = State.RETRIEVING_ACCESS_TOKEN;
			new RetrieveAccessTokenTask().execute();
		}
	}

	@UiThread private void onAuthorizationError(Exception e)
	{
		Toast.makeText(getActivity(), R.string.oauth_communication_error, Toast.LENGTH_LONG).show();
		Log.e(TAG, "Error during authorization", e);

		onAuthorizationFailed();
	}

	@UiThread private void onAuthorizationSuccess()
	{
		state = State.POST_AUTHORIZATION;
		new PostAuthorizationTask().execute();
	}

	@UiThread private void onAuthorizationFailed()
	{
		SharedPreferences.Editor editor = prefs.edit();
		editor.remove(Prefs.OSM_USER_ID);
		editor.apply();

		applyOAuthConsumer(null);

		dismiss();
	}

	private void applyOAuthConsumer(OAuthConsumer c)
	{
		oAuth.saveConsumer(c);
		// Updating its oauth consumer will provide the new consumer for all daos out there (using this connection)
		globalOsmConnectionSingleton.setOAuth(oAuth.loadConsumer());
	}

	/* ---------------------------------------------------------------------------------------- */

	private class RetrieveRequestTokenTask extends InlineAsyncTask<String> {

		@Override protected String doInBackground() throws Exception
		{
			return provider.retrieveRequestToken(consumer, CALLBACK_URL);
		}

		@Override public void onSuccess(String authorizeUrl)
		{
			if(getActivity() == null) return;

			Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(authorizeUrl));
			intent.putExtra(Browser.EXTRA_APPLICATION_ID, getActivity().getPackageName());
			intent.setFlags(Intent.FLAG_ACTIVITY_NO_HISTORY | Intent.FLAG_ACTIVITY_SINGLE_TOP); // TODO brauchmandas?
			startActivity(intent);
			state = State.AUTHENTICATING_IN_BROWSER;
		}

		@Override public void onError(Exception e)
		{
			if(getActivity() != null) onAuthorizationError(e);
		}
	}

	private class RetrieveAccessTokenTask extends InlineAsyncTask<Boolean> {

		@Override protected Boolean doInBackground() throws Exception
		{
			provider.retrieveAccessToken(consumer, verifier);
			List<String> permissions = new PermissionsDao(osmConnection).get();
			return permissions.containsAll(REQUIRED_PERMISSIONS);
		}

		@Override public void onSuccess(Boolean authorized)
		{
			if(getActivity() == null) return;

			if (authorized)
			{
				onAuthorizationSuccess();
			}
			else
			{
				Toast.makeText(getActivity(), R.string.oauth_failed_permissions, Toast.LENGTH_LONG).show();
				onAuthorizationFailed();
			}
		}

		@Override public void onError(Exception e)
		{
			if(getActivity() != null) onAuthorizationError(e);
		}
	}

	private class PostAuthorizationTask extends InlineAsyncTask<Void> {

		@Override protected Void doInBackground() throws Exception
		{
			long userId = new UserDao(osmConnection).getMine().id;
			SharedPreferences.Editor editor = prefs.edit();
			editor.putLong(Prefs.OSM_USER_ID, userId);
			editor.apply();
			return null;
		}

		@Override public void onSuccess(Void result)
		{
			Toast.makeText(getActivity(), R.string.pref_title_authorized_summary, Toast.LENGTH_LONG).show();
			applyOAuthConsumer(consumer);
			listener.onOAuthAuthorized();
			dismiss();
		}

		@Override public void onError(Exception e)
		{
			if(getActivity() != null) onAuthorizationError(e);
		}
	}
}