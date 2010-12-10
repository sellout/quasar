package blueeyes.health.metrics

import java.util.concurrent.atomic.AtomicLong
import scala.collection.JavaConversions._
import java.util.concurrent.ConcurrentHashMap
import collection.mutable.ConcurrentMap
import blueeyes.health.ConcurrentMaps
import blueeyes.json.JsonAST.{JInt, JField, JObject}

class ErrorStat extends ConcurrentMaps with Statistic{
  private val _count = new AtomicLong(0)
  private val _distribution : ConcurrentMap[Class[_], AtomicLong] = new ConcurrentHashMap[Class[_], AtomicLong]

  def +=[T <: Throwable](t: T): T = {
    _count.getAndAdd(1)

    createIfAbsent(t.getClass, _distribution, {new AtomicLong(0)}).getAndAdd(1)

    t
  }

  def count = _count.get

  def distribution: Map[Class[_], Long] = _distribution.toMap.mapValues(_.get)


  def toJValue = {
    val distributionJValue = distribution.toList.map(kv => JField(kv._1.getName, JInt(kv._2)))
    JObject(JField("errorCount", JInt(count)) :: JField("errorDistribution", JObject(distributionJValue)) :: Nil)
  }
}