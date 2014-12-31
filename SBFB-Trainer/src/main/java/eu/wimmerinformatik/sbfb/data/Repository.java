/*  vim: set sw=4 tabstop=4 fileencoding=UTF-8:
 *
 *  Copyright 2014 Matthias Wimmer
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package eu.wimmerinformatik.sbfb.data;

import java.io.IOException;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import eu.wimmerinformatik.sbfb.R;

import android.content.ContentValues;
import android.content.Context;
import android.content.res.XmlResourceParser;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.widget.SimpleCursorAdapter;

public class Repository extends SQLiteOpenHelper {
	private final Context context;
	private int answerIdSeq;
	private SQLiteDatabase database;
	private final String done;
	
	private static final int NUMBER_LEVELS = 5;

	public Repository(final Context context) {
		super(context, "topics", null, 8);
		done = context.getString(R.string.done);
		this.context = context;
	}

	@Override
	public void onCreate(SQLiteDatabase db) {
		// create databases
		db.beginTransaction();
		try {
			db.execSQL("CREATE TABLE topic (_id INT NOT NULL PRIMARY KEY, order_index INT NOT NULL UNIQUE, name TEXT NOT NULL)");
			db.execSQL("CREATE TABLE question (_id INT NOT NULL PRIMARY KEY, topic_id INT NOT NULL REFERENCES topic(_id) ON DELETE CASCADE, reference TEXT, question TEXT NOT NULL, level INT NOT NULL, next_time INT NOT NULL)");
			db.execSQL("CREATE TABLE answer (_id INT NOT NULL PRIMARY KEY, question_id INT NOT NULL REFERENCES question(_id) ON DELETE CASCADE, order_index INT NOT NULL, answer TEXT NOT NULL)");
			db.setTransactionSuccessful();
		} finally {
			db.endTransaction();
		}
				
		// fill with data
		try {
			final List<Topic> topics = new LinkedList<Topic>();
			final List<Question> questions = new LinkedList<Question>();
			final XmlResourceParser xmlResourceParser = context.getResources().getXml(R.xml.sbfbfragen);
			int eventType = xmlResourceParser.getEventType();
			Topic currentTopic = null;
			Question currentQuestion = null;
			boolean expectingAnswer = false;
			boolean expectingQuestion = false;
			int index = 0;
			while (eventType != XmlPullParser.END_DOCUMENT) {
				switch (eventType) {
				case XmlPullParser.START_TAG:
					final String tagName = xmlResourceParser.getName();
					if ("topic".equals(tagName)) {
						currentTopic = new Topic();
						currentTopic.setId(xmlResourceParser.getAttributeIntValue(null, "id", 0));
						currentTopic.setIndex(index++);
						currentTopic.setName(xmlResourceParser.getAttributeValue(null, "name"));
					} else if ("question".equals(tagName)) {
						currentQuestion = new Question();
						currentQuestion.setId(xmlResourceParser.getAttributeIntValue(null, "id", 0));
						currentQuestion.setReference(xmlResourceParser.getAttributeValue(null, "reference"));
						currentQuestion.setNextTime(new Date());
						currentQuestion.setTopicId(currentTopic.getId());
					} else if ("text".equals(tagName)) {
						expectingQuestion = true;
					} else if ("correct".equals(tagName)) {
						expectingAnswer = true;
					} else if ("incorrect".equals(tagName)) {
						expectingAnswer = true;
					}
					break;
				case XmlPullParser.TEXT:
					if (expectingQuestion) {
						currentQuestion.setQuestionText(xmlResourceParser.getText());
						expectingQuestion = false;
					}
					if (expectingAnswer) {
						currentQuestion.getAnswers().add(xmlResourceParser.getText());
						expectingAnswer = false;
					}
				case XmlPullParser.END_TAG:
					final String endTagName = xmlResourceParser.getName();
					if ("topic".equals(endTagName)) {
						topics.add(currentTopic);
						currentTopic = null;
					} else if ("question".equals(endTagName)) {
						questions.add(currentQuestion);
						currentQuestion = null;
					}
					break;
				}
				xmlResourceParser.next();
				eventType = xmlResourceParser.getEventType();
			}
			xmlResourceParser.close();
			
			db.beginTransaction();
			try {
				for (final Topic topic : topics) {
					save(db, topic);
				}
				for (final Question question : questions) {
					save(db, question);
				}
				db.setTransactionSuccessful();
			} finally {
				db.endTransaction();
			}
		} catch (final IOException ioe) {
			ioe.printStackTrace();
		} catch (XmlPullParserException e) {
			e.printStackTrace();
		}
	}
	
	public QuestionSelection selectQuestion(final int topicId) {
		final QuestionSelection result = new QuestionSelection();
		final List<Integer> possibleQuestions = new LinkedList<Integer>();
		final long now = new Date().getTime();
		
		int questionCount = 0;
		int openQuestions = 0;
		int maxProgress = 0;
		int currentProgress = 0;
		long soonestNextTime = 0;
		
		final Cursor c = getDb().query("question", new String[]{"_id", "level", "next_time"}, "topic_id=?", new String[]{Integer.toString(topicId)}, null, null, null, null);
		try {
			c.moveToNext();
			while (!c.isAfterLast()) {
				final int questionId = c.getInt(0);
				final int level = c.getInt(1);
				final long nextTime = c.getLong(2);
				
				questionCount++;
				maxProgress += NUMBER_LEVELS;
				currentProgress += level;
				if (level < NUMBER_LEVELS) {
					openQuestions++;
					
					if (nextTime > now) {
						if (soonestNextTime == 0 || soonestNextTime > nextTime) {
							soonestNextTime = nextTime;
						}
					} else {
						possibleQuestions.add(questionId);
					}	
				}
				
				c.moveToNext();
			}
			
		} finally {
			c.close();
		}
		
		result.setTotalQuestions(questionCount);
		result.setMaxProgress(maxProgress);
		result.setCurrentProgress(currentProgress);
		result.setOpenQuestions(openQuestions);
		result.setFinished(possibleQuestions.isEmpty() && soonestNextTime == 0);
		if (!possibleQuestions.isEmpty()) {
			Random rand = new Random();
			result.setSelectedQuestion(possibleQuestions.get(rand.nextInt(possibleQuestions.size())));
		} else if (soonestNextTime > 0) {
			result.setNextQuestion(new Date(soonestNextTime));
		}
		
		return result;
	}
	
	public Question getQuestion(final int questionId) {
		final Question question = new Question();
		
		final Cursor c = getDb().query("question", new String[]{"_id", "topic_id", "reference", "question", "level", "next_time"}, "_id=?", new String[]{Integer.toString(questionId)}, null, null, null, null);
		try {
			c.moveToNext();
			if (c.isAfterLast()) {
				return null;
			}
			question.setId(c.getInt(0));
			question.setTopicId(c.getInt(1));
			question.setReference(c.getString(2));
			question.setQuestionText(c.getString(3));
			question.setLevel(c.getInt(4));
			question.setNextTime(new Date(c.getLong(5)));
		} finally {
			c.close();
		}
		
		// _id INT NOT NULL PRIMARY KEY, question_id INT NOT NULL REFERENCES question(id) ON DELETE CASCADE, order_index INT NOT NULL, answer TEXT
		final Cursor answer = getDb().query("answer", new String[]{"answer"}, "question_id=?", new String[]{Integer.toString(questionId)}, null, null, "order_index");
		try {
			answer.moveToNext();
			while (!answer.isAfterLast()) {
				question.getAnswers().add(answer.getString(0));
				answer.moveToNext();
			}	
		} finally {
			answer.close();
		}
		
		return question;
	}

    public Topic getTopic(final int topicId) {
		final Topic topic = new Topic();
            
		final Cursor c = getDb().query("topic", new String[]{"_id", "order_index", "name"}, "_id=?", new String[]{Integer.toString(topicId)}, null, null, null);
		try {
			c.moveToNext();
			if (c.isAfterLast()) {
				return null;
			}
			topic.setId(c.getInt(0));
			topic.setIndex(c.getInt(1));
			topic.setName(c.getString(2));
		} finally {
			c.close();
		}

		return topic;
    }

    public TopicStats getTopicStat(final int topicId) {
		final TopicStats stats = new TopicStats();
		stats.setLevels(NUMBER_LEVELS);
		stats.setQuestionsAtLevel(new int[NUMBER_LEVELS+1]);

		int currentProgress = 0;
		int maxProgress = 0;
		int questionCount = 0;

		final Cursor c = getDb().query("question", new String[]{"_id", "level"}, "topic_id=?", new String[]{Integer.toString(topicId)}, null, null, null, null);
		try {
			c.moveToNext();
			while (!c.isAfterLast()) {
				questionCount++;
				currentProgress += c.getInt(1);
				maxProgress += NUMBER_LEVELS;
				stats.getQuestionsAtLevel()[c.getInt(1)]++;
				c.moveToNext();
			}
		} finally {
			c.close();
		}

		stats.setCurrentProgress(currentProgress);
		stats.setMaxProgress(maxProgress);
		stats.setQuestionCount(questionCount);

		return stats;
    }
	
	public void answeredCorrect(final int questionId) {
		final Question question = getQuestion(questionId);
		final int newLevel = question.getLevel() + 1;
		
		updateAnswered(questionId, newLevel);	
	}
	
	public void answeredIncorrect(final int questionId) {
		final Question question = getQuestion(questionId);
		final int newLevel = question.getLevel() <= 0 ? 0 : question.getLevel() - 1;
		
		updateAnswered(questionId, newLevel);		
	}
	
	public void continueNow(final int topicId) {
		final ContentValues updates = new ContentValues();
		updates.put("next_time", new Date().getTime());
		getDb().update("question", updates, "topic_id=?", new String[]{Integer.toString(topicId)});
	}
	
	public void resetTopic(final int topicId) {
		final ContentValues updates = new ContentValues();
		updates.put("next_time", new Date().getTime());
		updates.put("level", 0L);
		getDb().update("question", updates, "topic_id=?", new String[]{Integer.toString(topicId)});		
	}
	
	public void setTopicsInSimpleCursorAdapter(final SimpleCursorAdapter adapter) {
		final Cursor c = getTopicsCursor(getDb());
		adapter.changeCursor(c);
	}
	
	public Cursor getTopicsCursor(final SQLiteDatabase db) {
		final Cursor cursor = db.rawQuery("SELECT t._id AS _id, t.order_index AS order_index, t.name AS name, CASE WHEN MIN(level) >= " + NUMBER_LEVELS + " THEN ? ELSE SUM(CASE WHEN level < " + NUMBER_LEVELS +" THEN 1 ELSE 0 END) END AS status, MIN(CASE WHEN level >= " + NUMBER_LEVELS + " THEN NULL ELSE next_time END) AS next_question FROM topic t LEFT JOIN question q ON q.topic_id = t._id GROUP BY t._id, t.order_index, t.name ORDER BY t.order_index", new String[]{done});
		return cursor;
	}
	
	private void updateAnswered(final int questionId, final int newLevel) {
		final long newNextTime = new Date().getTime() + waitingTimeOnLevel(newLevel);
		
		final ContentValues updates = new ContentValues();
		updates.put("level", newLevel);
		updates.put("next_time", newNextTime);
		
		getDb().update("question", updates, "_id=?", new String[]{Integer.toString(questionId)});
	}
	
	private long waitingTimeOnLevel(final int level) {
		return level <= 0 ? 15000L :
			level == 1 ? 60000L :
			level == 2 ? 30*60000L :
			level == 3 ? 86400000L :
			level == 4 ? 3*86400000L :
			0;
	}

	private void save(final SQLiteDatabase db, Topic currentTopic) {
		final ContentValues contentValues = new ContentValues();
		contentValues.put("_id", currentTopic.getId());
		contentValues.put("order_index", currentTopic.getIndex());
		contentValues.put("name", currentTopic.getName());
		db.insert("topic", null, contentValues);
	}
	
	private void save(final SQLiteDatabase db, final Question question) {
		final ContentValues contentValues = new ContentValues();
		contentValues.put("_id", question.getId());
		contentValues.put("topic_id", question.getTopicId());
		contentValues.put("reference", question.getReference());
		contentValues.put("question", question.getQuestionText());
		contentValues.put("level", 0);
		contentValues.put("next_time", question.getNextTime().getTime());
		db.insert("question", null, contentValues);
			
		int answerIndex = 0;
		for (final String answer : question.getAnswers()) {
			contentValues.clear();
			contentValues.put("_id", ++answerIdSeq);
			contentValues.put("question_id", question.getId());
			contentValues.put("order_index", answerIndex++);
			contentValues.put("answer", answer);
			db.insert("answer", null, contentValues);
		}
	}
	
	private SQLiteDatabase getDb() {
		if (database == null) {
			database = this.getWritableDatabase();
		}
		return database;
	}

	@Override
	public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
		if (oldVersion <= 2) {
			final ContentValues updates = new ContentValues();
			updates.put("question", "Welches Funkzeugnis ist mindestens erforderlich, um mit einer Seefunkstelle auf einem Sportfahrzeug am Weltweiten Seenot- und Sicherheitsfunksystem (GMDSS) im Seegebiet A3 teilnehmen zu können?");
			db.update("question", updates, "_id=?", new String[]{"4408"});
		}
		if (oldVersion <= 3) {
			final ContentValues updates = new ContentValues();
			updates.put("answer", "Dasjenige Fahrzeug muss ausweichen, welches das andere an seiner Steuerbordseite hat.");
			db.update("answer", updates, "question_id=? AND order_index=?", new String[]{"8959", "0"});
			updates.clear();
			updates.put("answer", "Dasjenige Fahrzeug muss ausweichen, welches das andere an seiner Backbordseite hat.");
			db.update("answer", updates, "question_id=? AND order_index=?", new String[]{"8959", "1"});
			updates.clear();
			updates.put("answer", "Wasserflächen, auf denen mit Wasserski oder Wassermotorrädern gefahren werden darf.");
			db.update("answer", updates, "question_id=? AND order_index=?", new String[]{"8969", "0"});
			updates.clear();
			updates.put("answer", "Genehmigungspflichtige Übungsstrecke für das Fahren mit Wasserski oder Wassermotorrädern.");
			db.update("answer", updates, "question_id=? AND order_index=?", new String[]{"8969", "1"});
			updates.clear();
			updates.put("answer", "Fahren mit Wasserski oder Wassermotorrädern erlaubt. Wasserskiläufer und Wassermotorräder haben Vorfahrt.");
			db.update("answer", updates, "question_id=? AND order_index=?", new String[]{"8969", "2"});
			updates.clear();
			updates.put("answer", "Gasleitung entleeren und für Lüftung sorgen. Außerdem keine elektrischen Schalter betätigen und keine Telefone benutzen.");
			db.update("answer", updates, "question_id=? AND order_index=?", new String[]{"9008", "1"});
			updates.clear();
			updates.put("answer", "Luftzufuhr verhindern, Feuerlöscher erst am Brandherd einsetzen und das Feuer möglichst von unten bekämpfen.");
			db.update("answer", updates, "question_id=? AND order_index=?", new String[]{"9013", "0"});
			updates.clear();
			updates.put("answer", "Ausweichpflichtig ist das Fahrzeug, welches das andere an seiner Backbordseite sieht.");
			db.update("answer", updates, "question_id=? AND order_index=?", new String[]{"9122", "3"});
		}
		if (oldVersion <= 4) {
			final ContentValues updates = new ContentValues();
			updates.put("answer", "Er muss die Geschwindigkeit anpassen und soweit wie möglich in der Fahrwassermitte bleiben, gegebenenfalls besondere Geschwindigkeitsbegrenzungen und Fahrtbeschränkungen beachten.");
			db.update("answer", updates, "question_id=? AND order_index=?", new String[]{"9182", "0"});
			updates.clear();
			updates.put("answer", "Er muss die Geschwindigkeit anpassen und soweit wie möglich in der Fahrwassermitte bleiben, besondere Geschwindigkeitsbegrenzungen und Fahrtbeschränkungen sind nicht zu beachten.");
			db.update("answer", updates, "question_id=? AND order_index=?", new String[]{"9182", "3"});
		}
		if (oldVersion <= 5) {
		    final ContentValues updates = new ContentValues();
		    updates.put("answer", "Für Sportboote mit mehr als 11,03 kW (15 PS) Nutzleistung, auf dem Rhein von mehr als 3,68 kW (5 PS) Nutzleistung, und weniger als 15 m Länge.");
		    db.update("answer", updates, "question_id=? AND order_index=?", new String[]{"9018", "0"});
		    updates.clear();
		    updates.put("answer", "Für Sportboote von weniger als 11,03 kW (15 PS) Nutzleistung und mehr als 15 m Länge.");
		    db.update("answer", updates, "question_id=? AND order_index=?", new String[]{"9018", "1"});
		    updates.clear();
		    updates.put("answer", "Für Sportboote von mehr als 11,03 kW (15 PS) Nutzleistung und mehr als 15 m Länge.");
		    db.update("answer", updates, "question_id=? AND order_index=?", new String[]{"9018", "2"});
		    updates.clear();
		    updates.put("answer", "Für Sportboote von weniger als 11,03 kW (15 PS) Nutzleistung und weniger als 15 m Länge.");
		    db.update("answer", updates, "question_id=? AND order_index=?", new String[]{"9018", "3"});
		    updates.clear();
		    updates.put("question", "Welche Anforderungen neben der körperlichen und geistigen Tauglichkeit und fachlichen Eignung muss der Führer eines Sportbootes auf Binnenschifffahrtsstraßen, mit Ausnahme des Rheins, erfüllen, wenn die größte Nutzleistung der Antriebsmaschine 11,03 kW oder weniger beträgt?");
		    db.update("question", updates, "_id=?", new String[]{"9023"});
		    updates.clear();
		    updates.put("question", "Welche Anforderungen neben der körperlichen und geistigen Tauglichkeit und fachlichen Eignung muss der Führer eines Sportbootes auf dem Rhein erfüllen, wenn die Nutzleistung der Antriebsmaschine mehr als 3,68 kW beträgt?");
		    db.update("question", updates, "_id=?", new String[]{"9024"});
		    updates.clear();
		    updates.put("answer", "Schwimmendes Gerät bei der Arbeit. Vorbeifahrt an der grünen Seite gestattet; rote Seite gesperrt.");
		    db.update("answer", updates, "question_id=? AND order_index=?", new String[]{"9094", "0"});
		    updates.clear();
		    updates.put("answer", "Schwimmendes Gerät bei der Arbeit. Vorbeifahrt an der grünen Seite gestattet; rote Seite gesperrt. Sog und Wellenschlag vermeiden.");
		    db.update("answer", updates, "question_id=? AND order_index=?", new String[]{"9094", "1"});
		    updates.clear();
		    updates.put("answer", "Schwimmendes Gerät bei der Arbeit. Vorbeifahrt an der grünen Seite gestattet. Vorbeifahrt an der roten Seite mit unverminderter Geschwindigkeit möglich.");
		    db.update("answer", updates, "question_id=? AND order_index=?", new String[]{"9094", "2"});
		    updates.clear();
		    updates.put("answer", "Schwimmendes Gerät bei der Arbeit. Vorbeifahrt an der grünen Seite gestattet; rote Seite gesperrt.");
		    db.update("answer", updates, "question_id=? AND order_index=?", new String[]{"9095", "0"});
		    updates.clear();
		    updates.put("answer", "Schwimmendes Gerät bei der Arbeit. Vorbeifahrt an der grünen Seite gestattet; rote Seite gesperrt. Sog und Wellenschlag vermeiden.");
		    db.update("answer", updates, "question_id=? AND order_index=?", new String[]{"9095", "1"});
		    updates.clear();
		    updates.put("answer", "Fahrverbot für Fahrzeuge ohne Antriebsmaschine.");
		    db.update("answer", updates, "question_id=? AND order_index=?", new String[]{"9102", "2"});
		    updates.clear();
		    updates.put("answer", "Fahrverbot für Kleinfahrzeuge ohne laufende Antriebsmaschine.");
		    db.update("answer", updates, "question_id=? AND order_index=?", new String[]{"9102", "3"});
		    updates.clear();
		    updates.put("question", "Wie muss sich ein kreuzendes Kleinfahrzeug unter Segel am Wind in der Nähe eines Ufers gegenüber einem anderen Kleinfahrzeug verhalten?");
		    db.update("question", updates, "_id=?", new String[]{"9124"});
		    updates.clear();
		    updates.put("question", "Ein Fahrzeug unter Segel kreuzt eine Binnenschifffahrtsstraße. In der Fahrwassermitte kommt ihm ein Kleinfahrzeug mit Maschinenantrieb zu Berg entgegen. Wer ist ausweichpflichtig?");
		    db.update("question", updates, "_id=?", new String[]{"9129"});
		    updates.clear();
		    updates.put("answer", "Kreisförmiges Schwenken der Arme oder eines Gegenstandes.");
		    db.update("answer", updates, "question_id=? AND order_index=?", new String[]{"9157", "0"});
		    updates.clear();
		}
		if (oldVersion <= 6) {
		    final ContentValues updates = new ContentValues();
		    updates.put("answer", "Kleinfahrzeuge mit Maschinenantrieb und geschleppte Fahrzeuge.");
		    db.update("answer", updates, "question_id=? AND order_index=?", new String[]{"9123", "2"});
		    updates.clear();
		    updates.put("question", "Was bedeuten auf einem Fahrzeug drei blaue Lichter übereinander?");
		    db.update("question", updates, "_id=?", new String[]{"9075"});
		    updates.clear();
		}
		if (oldVersion <= 7) {
			final ContentValues updates = new ContentValues();
			updates.put("answer", "Einfahrt verboten, Schließen der Schleuse wird vorbereitet.");
		    db.update("answer", updates, "question_id=? AND order_index=?", new String[]{"9062", "1"});
		    updates.clear();
			updates.put("answer", "Ausfahrt verboten, Schließen der Schleuse wird vorbereitet.");
		    db.update("answer", updates, "question_id=? AND order_index=?", new String[]{"9062", "3"});
		    updates.clear();
			updates.put("answer", "Fahrzeug hat brennbare Stoffe geladen, Abstand beim Stillliegen 10 m.");
		    db.update("answer", updates, "question_id=? AND order_index=?", new String[]{"9072", "0"});
		    updates.clear();
			updates.put("answer", "B ist ausweichpflichtig. Das leeseitige Boot muss dem luvseitigen ausweichen.");
		    db.update("answer", updates, "question_id=? AND order_index=?", new String[]{"9131", "3"});
		    updates.clear();
			updates.put("answer", "Die der Wettsegelbestimmungen.");
		    db.update("answer", updates, "question_id=? AND order_index=?", new String[]{"9136", "1"});
		    updates.clear();
			updates.put("answer", "Ein Fehlerstromschutzschalter.");
		    db.update("answer", updates, "question_id=? AND order_index=?", new String[]{"9154", "0"});
		    updates.clear();
			updates.put("answer", "Das Achterliek killt, das Unterliek wird übermäßig gereckt.");
		    db.update("answer", updates, "question_id=? AND order_index=?", new String[]{"9233", "0"});
		    updates.clear();
			updates.put("answer", "Das Vorliek killt, das Unterliek wird übermäßig gereckt.");
		    db.update("answer", updates, "question_id=? AND order_index=?", new String[]{"9233", "1"});
		    updates.clear();
			updates.put("answer", "Das Unterliek killt, das Achterliek wird übermäßig gereckt.");
		    db.update("answer", updates, "question_id=? AND order_index=?", new String[]{"9233", "2"});
		    updates.clear();
		    updates.put("question", "Welche Funktion haben gelbe Tonnen mit einem Radarreflektor vor Brückenpfeilern?");
		    db.update("question", updates, "_id=?", new String[]{"9187"});
		    updates.clear();
		}
	}
}
