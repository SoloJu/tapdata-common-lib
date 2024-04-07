package io.tapdata.pdk.core.utils;

import io.tapdata.ErrorCodeConfig;
import io.tapdata.ErrorCodeEntity;
import io.tapdata.entity.logger.TapLogger;
import io.tapdata.exception.TapCodeException;
import io.tapdata.exception.TapPdkBaseException;
import io.tapdata.pdk.apis.context.TapConnectionContext;
import io.tapdata.pdk.apis.functions.PDKMethod;
import io.tapdata.pdk.apis.functions.connection.ErrorHandleFunction;
import io.tapdata.pdk.apis.functions.connection.RetryOptions;
import io.tapdata.pdk.core.api.Node;
import io.tapdata.pdk.core.entity.params.PDKMethodInvoker;
import io.tapdata.pdk.core.error.TapPdkRunnerExCode_18;
import io.tapdata.pdk.core.error.TapPdkRunnerUnknownException;
import io.tapdata.pdk.core.executor.ExecutorsManager;

import java.io.IOException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.BiPredicate;

/**
 * @author samuel
 * @Description
 * @create 2022-11-30 11:38
 **/
public class RetryUtils extends CommonUtils {
	private static final AutoRetryPolicy autoRetryPolicy = AutoRetryPolicy.ALWAYS;
	public static final String LOG_PREFIX = "[Auto Retry] ";
	public static final long DEFAULT_RETRY_PERIOD_SECONDS = 60L;
	private static List<Class<? extends Throwable>> defaultRetryIncludeList;
	private static BiPredicate<Throwable, Class<? extends Throwable>> matchFilter;

	static {
		defaultRetryIncludeList = new ArrayList<Class<? extends Throwable>>() {{
			add(IOException.class);
			add(SQLRecoverableException.class);
			add(SQLTimeoutException.class);
		}};
		matchFilter = (throwable, aClass) -> null != matchThrowable(throwable, aClass);
	}

	private static boolean needDefaultRetry(Throwable throwable) {
		for (Class<? extends Throwable> match : defaultRetryIncludeList) {
			if (matchFilter.test(throwable, match)) {
				return true;
			}
		}
		return false;
	}

	public static void autoRetry(Node node, PDKMethod method, PDKMethodInvoker invoker) {
		CommonUtils.AnyError runnable = invoker.getR();
		String message = invoker.getMessage();
		String logTag = invoker.getLogTag();
		boolean async = invoker.isAsync();
		boolean doRetry = false;
		long retryPeriodSeconds = invoker.getRetryPeriodSeconds() <= 0 ? DEFAULT_RETRY_PERIOD_SECONDS : invoker.getRetryPeriodSeconds();
		while (invoker.getRetryTimes() >= 0) {
			try {
				runnable.run();
				if (doRetry) {
					Optional.ofNullable(invoker.getLogListener())
							.ifPresent(log -> log.info(LOG_PREFIX + String.format("Method (%s) retry succeed", method.name().toLowerCase())));
					Optional.ofNullable(invoker.getClearFunctionRetry()).ifPresent(Runnable::run);
					Optional.ofNullable(invoker.getResetRetry()).ifPresent(Runnable::run);
				}
				break;
			} catch (Throwable errThrowable) {
				if (invoker.isEnableSkipErrorEvent()) {
					if (errThrowable instanceof TapCodeException) {
						String code = ((TapCodeException) errThrowable).getCode();
						ErrorCodeEntity errorCode = ErrorCodeConfig.getInstance().getErrorCode(code);
						if (null != errorCode && errorCode.isSkippable()) {
							if (!errorCode.isRecoverable()) {
								Optional.ofNullable(invoker.getResetRetry()).ifPresent(Runnable::run);
							}
							throw (TapCodeException) errThrowable;
						}
					}
				}
				CommonUtils.FunctionAndContext functionAndContext = CommonUtils.FunctionAndContext.create();
				CommonUtils.prepareFunctionAndContextForNode(node, functionAndContext);
				ErrorHandleFunction errorHandleFunction = functionAndContext.errorHandleFunction();
				switch (autoRetryPolicy) {
					case WHEN_NEED:
						if (null == errorHandleFunction) {
							TapLogger.debug(logTag, "This PDK data source not support retry. ");
							Optional.ofNullable(invoker.getResetRetry()).ifPresent(Runnable::run);
							wrapAndThrowError(errThrowable);
						}
						break;
					case ALWAYS:
					default:
						break;
				}

				long retryTimes = invoker.getRetryTimes();
				if (retryTimes > 0) {
					boolean needDefaultRetry = needDefaultRetry(errThrowable);
					RetryOptions retryOptions = callErrorHandleFunctionIfNeed(method, message, errThrowable, errorHandleFunction, functionAndContext.tapConnectionContext());
					retryFailed(method, invoker, message, retryPeriodSeconds, errThrowable, retryTimes, needDefaultRetry, retryOptions);
					if (!doRetry) {
						Optional.ofNullable(invoker.getSignFunctionRetry()).ifPresent(Runnable::run);
					}
					Optional.ofNullable(invoker.getStartRetry()).ifPresent(Runnable::run);
					if (async) {
						ExecutorsManager.getInstance().getScheduledExecutorService().schedule(() -> autoRetry(node, method, invoker), retryPeriodSeconds, TimeUnit.SECONDS);
						break;
					} else {
						synchronized (invoker) {
							try {
								invoker.wait(retryPeriodSeconds * 1000);
							} catch (InterruptedException e) {
								//e.printStackTrace();
							}
						}
					}
					callBeforeRetryMethodIfNeed(retryOptions, logTag);
					doRetry = true;
				} else {
                    Optional.ofNullable(invoker.getClearFunctionRetry()).ifPresent(Runnable::run);
					wrapAndThrowError(errThrowable);
				}
			}
		}
	}

	private static void retryFailed(PDKMethod method, PDKMethodInvoker invoker, String message, long retryPeriodSeconds, Throwable errThrowable, long retryTimes, boolean needDefaultRetry, RetryOptions retryOptions) {
		if (!needDefaultRetry) {
			throwIfNeed(invoker, retryOptions, message, errThrowable);
		}
		String errorCode;
		if (errThrowable instanceof TapPdkBaseException) {
			errorCode = ((TapPdkBaseException) errThrowable).getServerErrorCode();
		} else if (errThrowable instanceof TapCodeException) {
			errorCode = ((TapCodeException) errThrowable).getCode();
		} else {
			errorCode = "null";
		}
		Optional.ofNullable(invoker.getLogListener())
				.ifPresent(log -> log.warn(String.format(LOG_PREFIX + "Method (%s) encountered an error, triggering auto retry.\n - Error code: %s, message: %s\n - Remaining retry %s time(s)\n - Period %s second(s)",
						method.name().toLowerCase(), errorCode, errThrowable.getMessage(), invoker.getRetryTimes(), retryPeriodSeconds)));
		invoker.setRetryTimes(retryTimes - 1);
	}

	public static void autoRetry(PDKMethod method, PDKMethodInvoker invoker) {
		CommonUtils.AnyError runnable = invoker.getR();
		String message = invoker.getMessage();
		String logTag = invoker.getLogTag();
		boolean async = invoker.isAsync();
		boolean doRetry = false;
		long retryPeriodSeconds = invoker.getRetryPeriodSeconds() <= 0 ? DEFAULT_RETRY_PERIOD_SECONDS : invoker.getRetryPeriodSeconds();
		while (invoker.getRetryTimes() >= 0) {
			try {
				runnable.run();
				if (doRetry) {
					Optional.ofNullable(invoker.getLogListener())
							.ifPresent(log -> log.info(LOG_PREFIX + String.format("Method (%s) retry succeed", method.name().toLowerCase())));
					Optional.ofNullable(invoker.getClearFunctionRetry()).ifPresent(Runnable::run);
					Optional.ofNullable(invoker.getResetRetry()).ifPresent(Runnable::run);
				}
				break;
			} catch (Throwable errThrowable) {
				long retryTimes = invoker.getRetryTimes();
				if (retryTimes > 0) {
					boolean needDefaultRetry = needDefaultRetry(errThrowable);
					RetryOptions retryOptions = callErrorHandleFunctionIfNeed(errThrowable);
					retryFailed(method, invoker, message, retryPeriodSeconds, errThrowable, retryTimes, needDefaultRetry, retryOptions);
					if (!doRetry) {
						Optional.ofNullable(invoker.getSignFunctionRetry()).ifPresent(Runnable::run);
					}
					Optional.ofNullable(invoker.getStartRetry()).ifPresent(Runnable::run);
					if (async) {
						ExecutorsManager.getInstance().getScheduledExecutorService().schedule(() -> autoRetry(method, invoker), retryPeriodSeconds, TimeUnit.SECONDS);
						break;
					} else {
						synchronized (invoker) {
							try {
								invoker.wait(retryPeriodSeconds * 1000);
							} catch (InterruptedException e) {
								//e.printStackTrace();
							}
						}
					}
					callBeforeRetryMethodIfNeed(retryOptions, logTag);
					doRetry = true;
				} else {
					Optional.ofNullable(invoker.getClearFunctionRetry()).ifPresent(Runnable::run);
					wrapAndThrowError(errThrowable);
				}
			}
		}
	}

	private static void wrapAndThrowError(Throwable errThrowable) {
		Throwable matchThrowable = CommonUtils.matchThrowable(errThrowable, TapCodeException.class);
		if (null != matchThrowable) {
			throw (TapCodeException) matchThrowable;
		}
		throw new TapPdkRunnerUnknownException(errThrowable);
	}

	private static Throwable getLastCause(Throwable e) {
		Throwable last = e;
		while (null != last.getCause()) {
			last = last.getCause();
		}
		return last;
	}

	private static RetryOptions callErrorHandleFunctionIfNeed(PDKMethod method, String message, Throwable errThrowable, ErrorHandleFunction function, TapConnectionContext tapConnectionContext) {
		RetryOptions retryOptions = null;
		if (null != function) {
			try {
				retryOptions = function.needRetry(tapConnectionContext, method, errThrowable);
			} catch (Throwable e) {
				throw new TapCodeException(TapPdkRunnerExCode_18.CALL_ERROR_HANDLE_API_ERROR, "Call error handle function failed", e);
			}
		}
		if (null == retryOptions) {
			retryOptions = RetryOptions.create();
		}
		if (errThrowable instanceof TapCodeException) {
			String code = ((TapCodeException) errThrowable).getCode();
			ErrorCodeEntity errorCode = ErrorCodeConfig.getInstance().getErrorCode(code);
			if (errorCode.isRecoverable()) {
				retryOptions.needRetry(true);
			} else {
				if (!TapPdkRunnerExCode_18.UNKNOWN_ERROR.equals(code)) {
					retryOptions.needRetry(false);
				}
			}
		}
		return retryOptions;
	}
	public static RetryOptions callErrorHandleFunctionIfNeed(Throwable errThrowable) {
		RetryOptions retryOptions = null;
		if (null == retryOptions) {
			retryOptions = RetryOptions.create();
		}
		Throwable throwable = CommonUtils.matchThrowable(errThrowable, TapCodeException.class);
		if (null != throwable) {
			String code = ((TapCodeException) throwable).getCode();
			ErrorCodeEntity errorCode = ErrorCodeConfig.getInstance().getErrorCode(code);
			if (errorCode.isRecoverable()) {
				retryOptions.needRetry(true);
			}
		}
		return retryOptions;
	}

	private static void throwIfNeed(PDKMethodInvoker invoker, RetryOptions retryOptions, String message, Throwable errThrowable) {
		if (null == retryOptions) {
			return;
		}
		try {
			if (!retryOptions.isNeedRetry()) {
				throw errThrowable;
			}
		} catch (Throwable e) {
			Optional.ofNullable(invoker.getResetRetry()).ifPresent(Runnable::run);
			wrapAndThrowError(e);
		}
	}

	private static void callBeforeRetryMethodIfNeed(RetryOptions retryOptions, String logTag) {
		if (null == retryOptions) {
			return;
		}
		if (null == retryOptions.getBeforeRetryMethod()) {
			return;
		}
		CommonUtils.ignoreAnyError(() -> retryOptions.getBeforeRetryMethod().run(), logTag);
	}

	public static Throwable matchThrowable(Throwable throwable, Class<? extends Throwable> match) {
		if (null == throwable) {
			return null;
		}
		if (throwable.getClass().equals(match)) {
			return throwable;
		}
		List<Throwable> throwables = new ArrayList<>();
		throwables.add(throwable);
		Throwable matched = null;
		while (!Thread.currentThread().isInterrupted()) {
			Throwable cause = throwables.get(throwables.size() - 1).getCause();
			if (null == cause) {
				break;
			}
			if (throwables.contains(cause)) {
				break;
			}
			if (match.isInstance(cause)) {
				matched = cause;
				break;
			}
			throwables.add(cause);
		}
		return matched;
	}

	private enum AutoRetryPolicy {
		ALWAYS,
		WHEN_NEED,
	}
}
