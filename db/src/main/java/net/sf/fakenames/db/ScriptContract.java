/*
 * GroovyShell - Android harness for running Groovy programs
 *
 * Copyright © 2015 Alexander Rvachev
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * In addition, as a special exception, the copyright holders give
 * permission to link the code of portions of this program with independent
 * modules ("scripts") to produce an executable program, regardless of the license
 * terms of these independent modules, and to copy and distribute the resulting
 * script under terms of your choice, provided that you also meet, for each linked
 * independent module, the terms and conditions of the license of that module.
 * An independent module is a module which is not derived from or based on this library.
 * If you modify this library, you may extend this exception to your version of
 * the library, but you are not obligated to do so.  If you do not wish to do
 * so, delete this exception statement from your version.
 */
package net.sf.fakenames.db;

import android.app.ActivityGroup;
import com.annotatedsql.annotation.provider.Provider;
import com.annotatedsql.annotation.provider.Trigger;
import com.annotatedsql.annotation.provider.URI;
import com.annotatedsql.annotation.sql.Autoincrement;
import com.annotatedsql.annotation.sql.Column;
import com.annotatedsql.annotation.sql.NotNull;
import com.annotatedsql.annotation.sql.PrimaryKey;
import com.annotatedsql.annotation.sql.Schema;
import com.annotatedsql.annotation.sql.Table;
import com.annotatedsql.annotation.sql.Unique;

@Schema(className = "ScriptSchema", dbName = "scripts.db", dbVersion = 3)
@Provider(authority= ScriptContract.AUTHORITY, schemaClass="ScriptSchema", name="ScriptProviderProto", openHelperClass = "ScriptHelper")
public interface ScriptContract {
    String AUTHORITY = BuildConfig.APPLICATION_ID + ".provider";

    @Table(Scripts.TABLE_NAME)
    interface Scripts {
        @URI(customMimeType = "text/groovy")
        @Trigger(name = "performCleanup", type = Trigger.Type.DELETE, when = Trigger.When.BEFORE)
        String TABLE_NAME = "scripts";

        // our own unique identifier; never shown to user
        @NotNull @PrimaryKey @Autoincrement @Column(type = Column.Type.INTEGER)
        String SCRIPT_ID = "_id";

        // how the script was named by user
        @NotNull @Unique @Column(type = Column.Type.TEXT)
        String HUMAN_NAME = "script_uri";

        // where the script was taken from
        @NotNull @Column(type = Column.Type.TEXT)
        String SCRIPT_ORIGIN_URI = "source_uri";

        // how the script identifies itself
        @NotNull @Column(type = Column.Type.TEXT)
        String CLASS_NAME = "class_name";
    }
}