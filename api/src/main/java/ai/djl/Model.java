/*
 * Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"). You may not use this file except in compliance
 * with the License. A copy of the License is located at
 *
 * http://aws.amazon.com/apache2.0/
 *
 * or in the "license" file accompanying this file. This file is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
 * OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */
package ai.djl;

import ai.djl.engine.Engine;
import ai.djl.inference.Predictor;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.DataDesc;
import ai.djl.ndarray.types.DataType;
import ai.djl.nn.Block;
import ai.djl.training.Trainer;
import ai.djl.training.TrainingConfig;
import ai.djl.translate.Translator;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;

/**
 * A model is a collection of artifacts that is created by the training process.
 *
 * <p>A deep learning model usually contains the following parts:
 *
 * <ul>
 *   <li>Graph: aka Symbols in MXNet, model in Keras, Block in Pytorch
 *   <li>Parameters: weights
 *   <li>Input/Output information: input and output parameter names, shape, etc.
 *   <li>Other artifacts: e.g. dictionary for classification
 * </ul>
 *
 * <p>In a common inference case, the model is usually loaded from a file. Once the model is loaded,
 * you can create {@link Predictor} with the loaded model and call {@link Predictor#predict(Object)}
 * to get the inference result.
 *
 * <pre>
 * Model model = <b>Model.load</b>(modelDir, modelName);
 *
 * // User must implement Translator interface, read Translator for detail.
 * Translator translator = new MyTranslator();
 *
 * try (Predictor&lt;String, String&gt; predictor = <b>model.newPredictor</b>(translator)) {
 *   String result = predictor.<b>predict</b>("What's up");
 * }
 * </pre>
 *
 * @see Model#load(Path, String)
 * @see Predictor
 * @see Translator
 */
public interface Model extends AutoCloseable {

    /**
     * Create an empty model instance.
     *
     * @return a new Model instance
     */
    static Model newInstance() {
        return newInstance(Device.defaultDevice());
    }

    /**
     * Create an empty model instance on specified {@link Device}.
     *
     * @param device the device that model to be loaded
     * @return a new Model instance
     */
    static Model newInstance(Device device) {
        return Engine.getInstance().newModel(device);
    }

    /**
     * Loads the model from the {@code modelPath}.
     *
     * @param modelPath the directory or file path of the model location
     * @throws IOException IO exception happened in loading
     */
    default void load(Path modelPath) throws IOException {
        load(modelPath, modelPath.toFile().getName(), null);
    }

    /**
     * Loads the model from the {@code modelPath} and the given name.
     *
     * @param modelPath the directory or file path of the model location
     * @param modelName model file name or assigned name
     * @throws IOException IO exception happened in loading
     */
    default void load(Path modelPath, String modelName) throws IOException {
        load(modelPath, modelName, null);
    }

    /**
     * Loads the model from the {@code modelPath} with the name and options provided.
     *
     * @param modelPath the directory or file path of the model location
     * @param modelName model file name or assigned name
     * @param options engine specific load model options, see document for each engine
     * @throws IOException IO exception happened in loading
     */
    void load(Path modelPath, String modelName, Map<String, String> options) throws IOException;

    /**
     * Saves the model to specified {@code modelPath} with the name provided.
     *
     * @param modelPath the directory or file path of the model location
     * @param modelName model file name or assigned name
     * @throws IOException IO exception happened in loading
     */
    void save(Path modelPath, String modelName) throws IOException;

    /**
     * Get the block from the Model.
     *
     * @return {@link Block}
     */
    Block getBlock();

    void setBlock(Block block);

    /**
     * Get the NDArray Manager from the model.
     *
     * @return {@link NDManager}
     */
    NDManager getNDManager();

    /**
     * Creates a new {@link Trainer} instance for a Model.
     *
     * @param trainingConfig training configuration settings
     * @return Trainer
     */
    Trainer newTrainer(TrainingConfig trainingConfig);

    /**
     * Creates a new Predictor based on the model.
     *
     * @param translator The Object used for preprocessing and post processing
     * @param <I> Input object for preprocessing
     * @param <O> Output object come from postprocessing
     * @return instance of {@code Predictor}
     */
    <I, O> Predictor<I, O> newPredictor(Translator<I, O> translator);

    /**
     * Returns the input descriptor of the model.
     *
     * <p>It contains the information that can be extracted from the model, usually name, shape,
     * layout and DataType.
     *
     * @return Array of {@link DataDesc}
     */
    DataDesc[] describeInput();

    /**
     * Returns the output descriptor of the model.
     *
     * <p>It contains the output information that can be obtained from the model
     *
     * @return Array of {@link DataDesc}
     */
    DataDesc[] describeOutput();

    /**
     * Returns artifact names associated with the model.
     *
     * @return array of artifact names
     */
    String[] getArtifactNames();

    /**
     * If the specified artifact is not already cached, attempts to load the artifact using the
     * given function and cache it.
     *
     * <p>Model will cache loaded artifact, so user doesn't need to keep tracking it.
     *
     * <pre>{@code
     * String synset = model.getArtifact("synset.txt", k -> IOUtils.toString(k)));
     * }</pre>
     *
     * @param name name of the desired artifact
     * @param function the function to load artifact
     * @param <T> type of return artifact object
     * @return the current (existing or computed) artifact associated with the specified name, or
     *     null if the computed value is null
     * @throws IOException if an error occurs during loading resource
     * @throws ClassCastException if the cached artifact cannot be casted to target class
     */
    <T> T getArtifact(String name, Function<InputStream, T> function) throws IOException;

    /**
     * Finds an artifact resource with a given name in the model.
     *
     * @param name name of the desired artifact
     * @return A {@link java.net.URL} object or {@code null} if no artifact with this name is found
     * @throws IOException if an error occurs during loading resource
     */
    URL getArtifact(String name) throws IOException;

    /**
     * Finds an artifact resource with a given name in the model.
     *
     * @param name name of the desired artifact
     * @return A {@link java.io.InputStream} object or {@code null} if no resource with this name is
     *     found
     * @throws IOException if an error occurs during loading resource
     */
    InputStream getArtifactAsStream(String name) throws IOException;

    void setDataType(DataType dataType);

    DataType getDataType();

    /**
     * Casts the model to support a different precision level.
     *
     * <p>For example, you can cast the precision from Float to Int
     *
     * @param dataType the target dataType you would like to cast to
     */
    void cast(DataType dataType);

    /** {@inheritDoc} */
    @Override
    void close();
}