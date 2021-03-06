package eu.stamp_project.compare;


import eu.stamp_project.testrunner.EntryPoint;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * User: Simon
 * Date: 23/10/15
 * Time: 14:31
 */
public class ObjectLog {

    private static ObjectLog singleton;

    private Map<String, Observation> observations;
    private MethodsHandler methodsHandler;
    private int maxDeep = 3;

    private ObjectLog() {
        this.observations = new LinkedHashMap<>();
        this.methodsHandler = new MethodsHandler();
    }

    private static ObjectLog getSingleton() {
        if (singleton == null) {
            singleton = new ObjectLog();
        }
        return singleton;
    }

    public static synchronized void reset() {
        singleton = new ObjectLog();
    }

    public static synchronized void log(Object objectToObserve, String objectObservedAsString, String id) {
        getSingleton()._log(
                objectToObserve,
                objectToObserve,
                null,
                objectObservedAsString,
                id,
                0,
                new ArrayList<>()
        );
    }

    private synchronized void _log(Object startingObject,
                      Object objectToObserve,
                      Class<?> currentObservedClass,
                      String observedObjectAsString,
                      String id,
                      int deep,
                      List<Method> methodsToReachCurrentObject) {
        if (deep <= maxDeep) {
            final boolean primitive = Utils.isPrimitive(objectToObserve);
            final boolean primitiveArray = Utils.isPrimitiveArray(objectToObserve);
            final boolean primitiveCollectionOrMap = Utils.isNonEmptyPrimitiveCollectionOrMap(objectToObserve);
            if (objectToObserve == null) {
                addObservation(id, observedObjectAsString, null);
            } else if (isSerializable(objectToObserve) &&
                    (primitive || primitiveArray || primitiveCollectionOrMap)) {
                addObservation(id, observedObjectAsString, objectToObserve);
            } else if (Utils.isCollection(objectToObserve)) { // the object is empty here
                addObservation(id, observedObjectAsString + ".isEmpty()", ((Collection) objectToObserve).isEmpty());
            } else if (Utils.isMap(objectToObserve)) {
                addObservation(id, observedObjectAsString + ".isEmpty()", ((Map) objectToObserve).isEmpty());
            } else if (!objectToObserve.getClass().getName().toLowerCase().contains("mock")) {
                observeNotNullObject(
                        startingObject,
                        currentObservedClass == null ? objectToObserve.getClass() : currentObservedClass,
                        observedObjectAsString,
                        id,
                        deep,
                        methodsToReachCurrentObject
                );
            }
        }
    }

    public static boolean isSerializable(Object candidate) {
        try {
            new ObjectOutputStream(new ByteArrayOutputStream()).writeObject(candidate);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private synchronized void addObservation(String id, String observedObjectAsString, Object actualValue) {
        if (isSerializable(actualValue)) {
            if (actualValue instanceof String &&
                    // we forbid absolute paths
                    // we allow relative paths
                    // but it can be error-prone
                    // watch out
                    new File((String)actualValue).isAbsolute()) {
                return;
            }
            if (!observations.containsKey(id)) {
                observations.put(id, new Observation());
            }
            observations.get(id).add(observedObjectAsString, actualValue);
        }
    }

    private synchronized Object chainInvocationOfMethods(List<Method> methodsToInvoke, Object startingObject) throws FailToObserveException {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        FutureTask task = null;
        Object currentObject = null;
        try {
            try {
                task = new FutureTask<>(() -> methodsToInvoke.get(0).invoke(startingObject));
                executor.execute(task);
                currentObject = task.get(1, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new FailToObserveException();
            }
            for (int i = 1; i < methodsToInvoke.size(); i++) {
                Method method = methodsToInvoke.get(i);
                try {
                    final Object finalCurrentObject = currentObject;
                    task = new FutureTask<>(() -> method.invoke(finalCurrentObject));
                    executor.execute(task);
                    currentObject = task.get(1, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new FailToObserveException();
                }
            }
        } finally {
            if (task != null) {
                task.cancel(true);
            }
            executor.shutdown();
        }
        return currentObject;
    }

    private synchronized void observeNotNullObject(Object startingObject,
                                      Class<?> currentObservedClass,
                                      String stringObject,
                                      String id,
                                      int deep,
                                      List<Method> methodsToReachCurrentObject) {
        try {
            for (Method method : methodsHandler.getAllMethods(currentObservedClass)) {
                try {
                    final ArrayList<Method> tmpListOfMethodsToReachCurrentObject = new ArrayList<>(methodsToReachCurrentObject);
                    tmpListOfMethodsToReachCurrentObject.add(method);
                    final Object result = chainInvocationOfMethods(tmpListOfMethodsToReachCurrentObject, startingObject);
                    if (startingObject.getClass().isAnonymousClass()) {
                        _log(startingObject,
                                result,
                                method.getReturnType(),
                                "(" + stringObject + ")." + method.getName() + "()",
                                id,
                                deep + 1,
                                tmpListOfMethodsToReachCurrentObject
                        );
                    } else {
                        String nameOfVisibleClass = getVisibleClass(currentObservedClass);
                        _log(startingObject,
                                result,
                                method.getReturnType(),
                                "(" + nameOfVisibleClass + stringObject + ")." + method.getName() + "()",
                                id,
                                deep + 1,
                                tmpListOfMethodsToReachCurrentObject
                        );
                    }
                    tmpListOfMethodsToReachCurrentObject.remove(method);
                } catch (FailToObserveException ignored) {
                    // ignored, we just do nothing...
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private synchronized String getVisibleClass(Class<?> currentObservedClass) {
        if (currentObservedClass == null || currentObservedClass == Object.class) {
            return "";
        } else if (Modifier.isPrivate(currentObservedClass.getModifiers()) ||
                Modifier.isProtected(currentObservedClass.getModifiers())) {
            return getVisibleClass(currentObservedClass.getSuperclass());
        } else {
            return "(" + currentObservedClass.getCanonicalName() + ")";
        }
    }

    public synchronized static Map<String, Observation> getObservations() {
        if (getSingleton().observations.isEmpty()) {
            return load();
        } else {
            return getSingleton().observations;
        }
    }

    private static final String OBSERVATIONS_PATH_FILE_NAME = "target/dspot/observations.ser";

    public synchronized static void save() {
        final File file = new File(OBSERVATIONS_PATH_FILE_NAME);
        getSingleton().observations.values().forEach(Observation::purify);
        try (FileOutputStream fout = new FileOutputStream(OBSERVATIONS_PATH_FILE_NAME)) {
            try (ObjectOutputStream oos = new ObjectOutputStream(fout)) {
                oos.writeObject(getSingleton().observations);
                System.out.println(
                        String.format("File saved to the following path: %s",
                                file.getAbsolutePath())
                );
            } catch (Exception e) {
                e.printStackTrace();
                throw new RuntimeException(e);
            }
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

    public synchronized static Map<String, Observation> load() {
        try (FileInputStream fi = new FileInputStream(new File(
                (EntryPoint.workingDirectory != null ? // in case we modified the working directory
                        EntryPoint.workingDirectory.getAbsolutePath() + "/" : "") +
                        OBSERVATIONS_PATH_FILE_NAME))) {
            try (ObjectInputStream oi = new ObjectInputStream(fi)) {
                return (Map) oi.readObject();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

}