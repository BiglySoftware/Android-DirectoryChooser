/*
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */

package net.rdrei.android.dirchooser;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.annotation.UiThread;

class ThreadUtils {
	public interface RunnableWithObject<T>
	{
		public void run(T result);
	}

	@AnyThread
	public static void runOnUIThread(
			@UiThread @NonNull Runnable runnable) {
		new Handler(Looper.getMainLooper()).post(runnable);
	}

	@AnyThread
	public static <T> void runOnUIThread(
			@UiThread @NonNull RunnableWithObject<T> runnable, T value) {
		new Handler(Looper.getMainLooper()).post(() -> runnable.run(value));
	}

	public static void runOffUIThread(Runnable runnable, String name) {
		new Thread(runnable, name).start();
	}

	public static boolean isUIThread() {
		return Looper.getMainLooper().getThread() == Thread.currentThread();
	}
}
