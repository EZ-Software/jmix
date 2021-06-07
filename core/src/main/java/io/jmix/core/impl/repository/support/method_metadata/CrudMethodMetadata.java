/*
 * Copyright 2021 Haulmont.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.jmix.core.impl.repository.support.method_metadata;

public class CrudMethodMetadata {
    private final boolean applyConstraints;

    public CrudMethodMetadata(boolean applyConstraints) {
        this.applyConstraints = applyConstraints;
    }

    public boolean isApplyConstraints() {
        return applyConstraints;
    }


    @Override
    public String toString() {
        return String.format("[ApplyConstraints:%s]", applyConstraints);
    }

    public interface Accessor {
        CrudMethodMetadata getCrudMethodMetadata();
    }
}
