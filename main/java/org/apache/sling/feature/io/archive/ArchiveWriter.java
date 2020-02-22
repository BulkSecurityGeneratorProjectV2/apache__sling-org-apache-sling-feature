/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.sling.feature.io.archive;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.Deflater;

import org.apache.sling.feature.Artifact;
import org.apache.sling.feature.ArtifactId;
import org.apache.sling.feature.Extension;
import org.apache.sling.feature.ExtensionType;
import org.apache.sling.feature.Feature;
import org.apache.sling.feature.builder.ArtifactProvider;
import org.apache.sling.feature.io.json.FeatureJSONWriter;

/**
 * The feature archive writer can be used to create an archive based on a
 * feature model. The archive contains the feature model file and all artifacts.
 */
public class ArchiveWriter {

    /** The manifest header marking an archive as a feature archive. */
    public static final String MANIFEST_HEADER = "Feature-Archive-Version";

    /** Current support version of the feature model archive. */
    public static final int ARCHIVE_VERSION = 1;

    /** The directory in the archive holding the features */
    public static final String FEATURE_MODEL_PREFIX = "features/";

    /** The directory in the archive holding the artifacts */
    public static final String ARTIFACTS_PREFIX = "artifacts/";

    /**
     * Create a feature model archive. The output stream will not be closed by this
     * method. The caller must call {@link JarOutputStream#close()} or
     * {@link JarOutputStream#finish()} on the return output stream. The caller can
     * add additional files through the return stream.
     *
     * A feature model can be in different states: it might be a partial feature
     * model, a complete feature model or an assembled feature model. This method
     * takes the feature model as provided and only writes the listed bundles and
     * artifacts of this feature model into the archive. In general, the best
     * approach for sharing features is to archive {@link Feature#isComplete()
     * complete} features.
     *
     * @param out          The output stream to write to
     * @param baseManifest Optional base manifest used for creating the manifest.
     * @param provider     The artifact provider
     * @param features     The features model to archive
     * @return The jar output stream.
     * @throws IOException If anything goes wrong
     */
    public static JarOutputStream write(final OutputStream out,
            final Manifest baseManifest,
            final ArtifactProvider provider, final Feature... features)
    throws IOException {
        // create manifest
        final Manifest manifest = (baseManifest == null ? new Manifest() : new Manifest(baseManifest));
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue(MANIFEST_HEADER, String.valueOf(ARCHIVE_VERSION));

        // create archive
        final JarOutputStream jos = new JarOutputStream(out, manifest);

        // write models first with compression enabled
        jos.setLevel(Deflater.BEST_COMPRESSION);
        for (final Feature feature : features) {
            final JarEntry entry = new JarEntry(FEATURE_MODEL_PREFIX.concat(feature.getId().toMvnPath()));
            jos.putNextEntry(entry);
            final Writer writer = new OutputStreamWriter(jos, "UTF-8");
            FeatureJSONWriter.write(writer, feature);
            writer.flush();
            jos.closeEntry();
        }

        // write artifacts with compression disabled
        jos.setLevel(Deflater.NO_COMPRESSION);
        final byte[] buffer = new byte[1024*1024*256];

        final Set<ArtifactId> artifacts = new HashSet<>();

        for (final Feature feature : features) {
            for (final Artifact a : feature.getBundles()) {
                writeArtifact(artifacts, provider, a, jos, buffer);
            }

            for (final Extension e : feature.getExtensions()) {
                if (e.getType() == ExtensionType.ARTIFACTS) {
                    for (final Artifact a : e.getArtifacts()) {
                        writeArtifact(artifacts, provider, a, jos, buffer);
                    }
                }
            }
        }
        return jos;
    }

    private static void writeArtifact(final Set<ArtifactId> artifacts,
            final ArtifactProvider provider,
            final Artifact artifact,
            final JarOutputStream jos,
            final byte[] buffer) throws IOException {
        if ( artifacts.add(artifact.getId())) {
            final JarEntry artifactEntry = new JarEntry(ARTIFACTS_PREFIX + artifact.getId().toMvnPath());
            jos.putNextEntry(artifactEntry);

            final URL url = provider.provide(artifact.getId());
            if (url == null) {
                throw new IOException("Unable to find artifact " + artifact.getId().toMvnId());
            }
            try (final InputStream is = url.openStream()) {
                int l = 0;
                while ( (l = is.read(buffer)) > 0 ) {
                    jos.write(buffer, 0, l);
                }
            }
            jos.closeEntry();
        }
    }
}
