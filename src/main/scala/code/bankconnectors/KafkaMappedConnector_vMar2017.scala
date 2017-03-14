package code.bankconnectors

/*
Open Bank Project - API
Copyright (C) 2011-2017, TESOBE Ltd

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU Affero General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
GNU Affero General Public License for more details.

You should have received a copy of the GNU Affero General Public License
along with this program. If not, see http://www.gnu.org/licenses/.

Email: contact@tesobe.com
TESOBE Ltd
Osloerstrasse 16/17
Berlin 13359, Germany
*/

import java.text.{DateFormat, SimpleDateFormat}
import java.util.{Date, Locale, UUID}
import java.time.ZoneOffset.UTC
import java.time.ZonedDateTime

import code.accountholder.AccountHolders
import code.api.util.APIUtil.{MessageDocs, ResourceDoc}
import code.api.util.ErrorMessages
import code.api.v2_1_0._
import code.branches.Branches.{Branch, BranchId}
import code.branches.MappedBranch
import code.fx.{FXRate, fx}
import code.management.ImporterAPI.ImporterTransaction
import code.metadata.comments.{Comments, MappedComment}
import code.metadata.counterparties.{Counterparties, CounterpartyTrait, MappedCounterparty}
import code.metadata.narrative.MappedNarrative
import code.metadata.tags.{MappedTag, Tags}
import code.metadata.transactionimages.{MappedTransactionImage, TransactionImages}
import code.metadata.wheretags.{MappedWhereTag, WhereTags}
import code.model._
import code.model.dataAccess._
import code.products.Products.ProductCode
import code.transaction.MappedTransaction
import code.transactionrequests.{MappedTransactionRequest, TransactionRequestTypeCharge}
import code.transactionrequests.TransactionRequests._
import code.util.{Helper, TTLCache}
import code.views.Views
import net.liftweb.json
import net.liftweb.mapper._
import net.liftweb.util.Helpers._
import net.liftweb.util.Props
import net.liftweb.json._
import net.liftweb.common._
import code.products.MappedProduct
import code.products.Products.{Product, ProductCode}
import net.liftweb.json.Extraction
import net.liftweb.json.JsonAST.JValue

import scala.collection.immutable.Nil
import scala.collection.mutable.ArrayBuffer
import JsonAST._

object KafkaMappedConnector_vMar2017 extends Connector with Loggable {

  var producer = new KafkaProducer()
  var consumer = new KafkaConsumer()
  type AccountType = KafkaBankAccount

  // Local TTL Cache
  val cacheTTL              = Props.get("connector.cache.ttl.seconds", "0").toInt
  val cachedUser            = TTLCache[KafkaInboundValidatedUser](cacheTTL)
  val cachedBank            = TTLCache[KafkaInboundBank](cacheTTL)
  val cachedAccount         = TTLCache[KafkaInboundAccount](cacheTTL)
  val cachedBanks           = TTLCache[List[KafkaInboundBank]](cacheTTL)
  val cachedAccounts        = TTLCache[List[KafkaInboundAccount]](cacheTTL)
  val cachedPublicAccounts  = TTLCache[List[KafkaInboundAccount]](cacheTTL)
  val cachedUserAccounts    = TTLCache[List[KafkaInboundAccount]](cacheTTL)
  val cachedFxRate          = TTLCache[KafkaInboundFXRate](cacheTTL)
  val cachedCounterparty    = TTLCache[KafkaInboundCounterparty](cacheTTL)
  val cachedTransactionRequestTypeCharge = TTLCache[KafkaInboundTransactionRequestTypeCharge](cacheTTL)


  //
  // "Versioning" of the messages sent by this or similar connector might work like this:
  // Use Case Classes (e.g. KafkaInbound... KafkaOutbound... as below to describe the message structures.
  // Probably should be in a separate file e.g. Nov2016_messages.scala
  // Once the message format is STABLE, freeze the key/value pair names there. For now, new keys may be added but none modified.
  // If we want to add a new message format, create a new file e.g. March2017_messages.scala
  // Then add a suffix to the connector value i.e. instead of kafka we might have kafka_march_2017.
  // Then in this file, populate the different case classes depending on the connector name and send to Kafka
  //

  val formatVersion: String  = "Mar2017"

  implicit val formats = net.liftweb.json.DefaultFormats
  val messageDocs = ArrayBuffer[MessageDocs]()
  val simpleDateFormat: SimpleDateFormat = new SimpleDateFormat("dd/mm/yyyy")
  val exampleDateString: String = "22/08/2013"
  val exampleDate = simpleDateFormat.parse(exampleDateString)
  val emptyObjectJson: JValue = Extraction.decompose(Nil)
  
  messageDocs += MessageDocs(
    action = "obp.get.User",
    kafkaConnectorVersion = formatVersion,
    description = "getUser from kafka ",
    exampleRequestMessage = Extraction.decompose(GetUser(
      action = "obp.get.User",
      version = formatVersion,
      username = "",
      password = "")),
    exampleResponseMessage = emptyObjectJson,
    errorResponseMessages = emptyObjectJson :: Nil
  )
  
  def getUser(username: String, password: String): Box[InboundUser] = {
    for {
      req <- Full(GetUser(
        action = "obp.get.User",
        version = formatVersion,
        username = username,
        password = password))
      u <- tryo { cachedUser.getOrElseUpdate(req.toString, () => process(req).extract[KafkaInboundValidatedUser]) }
      recUsername <- tryo { u.displayName }
    } yield {
      if (username == u.displayName) new InboundUser(recUsername, password, recUsername)
      else null
    }
  }
  
  // TODO this is confused ? method name is updateUserAccountViews, but action is 'obp.get.Accounts'
  messageDocs += MessageDocs(
    action = "obp.get.Accounts",
    kafkaConnectorVersion = formatVersion,
    description = "updateUserAccountViews from kafka ",
    exampleRequestMessage = Extraction.decompose(UpdateUserAccountViews(
      action = "obp.get.Accounts",
      version = formatVersion,
      username = "",
      userId = "",
      bankId = "")),
    exampleResponseMessage = emptyObjectJson,
    errorResponseMessages = emptyObjectJson :: Nil
  )
  
  def updateUserAccountViews( user: ResourceUser ) = {
    val accounts: List[KafkaInboundAccount] = getBanks.flatMap { bank => {
      val bankId = bank.bankId.value
      logger.info(s"ObpJvm updateUserAccountViews for user.email ${user.email} user.name ${user.name} at bank ${bankId}")
      for {
        username <- tryo {user.name}
        req <- Full(UpdateUserAccountViews(
          action = "obp.get.Accounts",
          version = formatVersion,
          username = user.name,
          userId = user.name,
          bankId = bankId))
        
        // Generate random uuid to be used as request-response match id
        } yield {
          cachedUserAccounts.getOrElseUpdate(req.toString, () => process(req).extract[List[KafkaInboundAccount]])
        }
      }
    }.flatten

    val views = for {
      acc <- accounts
      username <- tryo {user.name}
      views <- tryo {createViews( BankId(acc.bankId),
        AccountId(acc.accountId),
        acc.owners.contains(username),
        acc.generate_public_view,
        acc.generate_accountants_view,
        acc.generate_auditors_view
      )}
      existing_views <- tryo {Views.views.vend.views(BankAccountUID(BankId(acc.bankId), AccountId(acc.accountId)))}
    } yield {
      setAccountOwner(username, BankId(acc.bankId), AccountId(acc.accountId), acc.owners)
      views.foreach(v => {
        Views.views.vend.addPermission(v.uid, user)
        logger.info(s"------------> updated view ${v.uid} for resourceuser ${user} and account ${acc}")
      })
      existing_views.filterNot(_.users.contains(user.resourceUserId)).foreach (v => {
        Views.views.vend.addPermission(v.uid, user)
        logger.info(s"------------> added resourceuser ${user} to view ${v.uid} for account ${acc}")
      })
    }
  }
  
  messageDocs += MessageDocs(
    action = "obp.get.Banks",
    kafkaConnectorVersion = formatVersion,
    description = "getBanks from kafka ",
    exampleRequestMessage = Extraction.decompose(GetBanks(
      action = "obp.get.Banks",
      version = formatVersion,
      username = "",
      userId = "")),
    exampleResponseMessage = emptyObjectJson,
    errorResponseMessages = emptyObjectJson :: Nil
  )

  //gets banks handled by this connector
  override def getBanks: List[Bank] = {
    val req = GetBanks(
      action = "obp.get.Banks",
      version = formatVersion,
      username = AuthUser.getCurrentResourceUserUserId,
      userId = AuthUser.getCurrentUserUsername)

    logger.debug(s"Kafka getBanks says: req is: $req")

    logger.debug(s"Kafka getBanks before cachedBanks.getOrElseUpdate")
    val rList = {
      cachedBanks.getOrElseUpdate( req.toString, () => process(req).extract[List[KafkaInboundBank]])
    }

    logger.debug(s"Kafka getBanks says rList is $rList")

    // Loop through list of responses and create entry for each
    val res = { for ( r <- rList ) yield {
        new KafkaBank(r)
      }
    }
    // Return list of results

    logger.debug(s"Kafka getBanks says res is $res")
    res
  }
  messageDocs += MessageDocs(
    action = "obp.get.ChallengeThreshold",
    kafkaConnectorVersion = formatVersion,
    description = "getChallengeThreshold from kafka ",
    exampleRequestMessage = Extraction.decompose(GetChallengeThreshold(
      action = "obp.get.ChallengeThreshold",
      version = formatVersion,
      bankId = "",
      accountId = "",
      viewId = "",
      transactionRequestType = "",
      currency = "",
      userId = "",
      username = "")),
    exampleResponseMessage = emptyObjectJson,
    errorResponseMessages = emptyObjectJson :: Nil
  )
  
  // Gets current challenge level for transaction request
  override def getChallengeThreshold(bankId: String, accountId: String, viewId: String, transactionRequestType: String, currency: String, userId: String, username: String): AmountOfMoney = {
    // Create argument list
    val req = GetChallengeThreshold(
      action = "obp.get.ChallengeThreshold",
      version = formatVersion,
      bankId = bankId,
      accountId = accountId,
      viewId = viewId,
      transactionRequestType = transactionRequestType,
      currency = currency,
      userId = userId,
      username = username)
    
    val r: Option[KafkaInboundChallengeLevel] = process(req).extractOpt[KafkaInboundChallengeLevel]
    // Return result
    r match {
      // Check does the response data match the requested data
      case Some(x)  => AmountOfMoney(x.currency, x.limit)
      case _ => {
        val limit = BigDecimal("0")
        val rate = fx.exchangeRate ("EUR", currency)
        val convertedLimit = fx.convert(limit, rate)
        AmountOfMoney(currency,convertedLimit.toString())
      }
    }
  }
  
  messageDocs += MessageDocs(
    action = "obp.get.ChargeLevel",
    kafkaConnectorVersion = formatVersion,
    description = "ChargeLevel from kafka ",
    exampleRequestMessage = Extraction.decompose(GetChargeLevel(
      action = "obp.get.ChargeLevel",
      version = formatVersion,
      bankId = "",
      accountId = "",
      viewId = "",
      userId = "",
      username = "",
      transactionRequestType = "",
      currency = "")),
    exampleResponseMessage = emptyObjectJson,
    errorResponseMessages = emptyObjectJson :: Nil
  )
  
  override def getChargeLevel(bankId: BankId,
                              accountId: AccountId,
                              viewId: ViewId,
                              userId: String,
                              username: String,
                              transactionRequestType: String,
                              currency: String): Box[AmountOfMoney] = {
    // Create argument list
    val req = GetChargeLevel(
      action = "obp.get.ChargeLevel",
      version = formatVersion,
      bankId = bankId.value,
      accountId = accountId.value,
      viewId = viewId.value,
      transactionRequestType = transactionRequestType,
      currency = currency,
      userId = userId,
      username = username
    )
   
    val r: Option[KafkaInboundChargeLevel] = process(req).extractOpt[KafkaInboundChargeLevel]
    // Return result
    val chargeValue = r match {
      // Check does the response data match the requested data
      case Some(x) => AmountOfMoney(x.currency, x.amount)
      case _ => {
        AmountOfMoney("EUR", "0.0001")
      }
    }
    Full(chargeValue)
  }
  
  messageDocs += MessageDocs(
    action = "obp.create.Challenge",
    kafkaConnectorVersion = formatVersion,
    description = "CreateChallenge from kafka ",
    exampleRequestMessage = Extraction.decompose(CreateChallenge(
      action = "obp.create.Challenge",
      version = formatVersion,
      bankId = "",
      accountId = "",
      userId = "",
      username = "",
      transactionRequestType = "",
      transactionRequestId = "")),
    exampleResponseMessage = emptyObjectJson,
    errorResponseMessages = emptyObjectJson :: Nil
  )
  
  override def createChallenge(bankId: BankId, accountId: AccountId, userId: String, transactionRequestType: TransactionRequestType, transactionRequestId: String) : Box[String] = {
    // Create argument list
    val req = CreateChallenge(
      action = "obp.create.Challenge",
      version = formatVersion,
      bankId = bankId.value,
      accountId = accountId.value,
      userId = userId,
      username = AuthUser.getCurrentUserUsername,
      transactionRequestType = transactionRequestType.value,
      transactionRequestId = transactionRequestId)
    
    val r: Option[KafkaInboundCreateChallange] = process(req).extractOpt[KafkaInboundCreateChallange]
    // Return result
    r match {
      // Check does the response data match the requested data
      case Some(x)  => Full(x.challengeId)
      case _        => Empty
    }
  }
  
  messageDocs += MessageDocs(
    action = "obp.validate.ChallengeAnswer",
    kafkaConnectorVersion = formatVersion,
    description = "validateChallengeAnswer from kafka ",
    exampleRequestMessage = Extraction.decompose(ValidateChallengeAnswer(
      action = "obp.validate.ChallengeAnswer",
      version = formatVersion,
      userId = "",
      username = "",
      challengeId = "",
      hashOfSuppliedAnswer = "")),
    exampleResponseMessage = emptyObjectJson,
    errorResponseMessages = emptyObjectJson :: Nil
  )
  override def validateChallengeAnswer(challengeId: String, hashOfSuppliedAnswer: String) : Box[Boolean] = {
    // Create argument list
    val req = ValidateChallengeAnswer(
      action = "obp.validate.ChallengeAnswer",
      version = formatVersion,
      userId = AuthUser.getCurrentResourceUserUserId,
      username = AuthUser.getCurrentUserUsername,
      challengeId = challengeId,
      hashOfSuppliedAnswer = hashOfSuppliedAnswer)

    val r: Option[KafkaInboundValidateChallangeAnswer] = process(req).extractOpt[KafkaInboundValidateChallangeAnswer]
    // Return result
    r match {
      // Check does the response data match the requested data
      case Some(x)  => Full(x.answer.toBoolean)
      case _        => Empty
    }
  }
  
  messageDocs += MessageDocs(
    action = "obp.get.Bank",
    kafkaConnectorVersion = formatVersion,
    description = "getBank from kafka ",
    exampleRequestMessage = Extraction.decompose(GetBank(
      action = "obp.get.Bank",
      version = formatVersion,
      bankId = "",
      userId = "",
      username = "")),
    exampleResponseMessage = emptyObjectJson,
    errorResponseMessages = emptyObjectJson :: Nil
  )
  override def getBank(bankid: BankId): Box[Bank] = {
    // Create argument list
    val req = GetBank(
      action = "obp.get.Bank",
      version = formatVersion,
      bankId = bankid.toString,
      userId = AuthUser.getCurrentResourceUserUserId,
      username = AuthUser.getCurrentUserUsername)
    
    val r = {
      cachedBank.getOrElseUpdate( req.toString, () => process(req).extract[KafkaInboundBank])
    }
    // Return result
    Full(new KafkaBank(r))
  }
  
  messageDocs += MessageDocs(
    action = "obp.get.Transaction",
    kafkaConnectorVersion = formatVersion,
    description = "getTransaction from kafka ",
    exampleRequestMessage = Extraction.decompose(GetTransaction(
      action = "obp.get.Transaction",
      version = formatVersion,
      userId = "",
      username = "",
      bankId = "",
      accountId = "",
      transactionId = "")),
    exampleResponseMessage = emptyObjectJson,
    errorResponseMessages = emptyObjectJson :: Nil
  )
  // Gets transaction identified by bankid, accountid and transactionId
  def getTransaction(bankId: BankId, accountId: AccountId, transactionId: TransactionId): Box[Transaction] = {
    val req = GetTransaction(
      action = "obp.get.Transaction",
      version = formatVersion,
      userId = AuthUser.getCurrentResourceUserUserId,
      username = AuthUser.getCurrentUserUsername,
      bankId = bankId.toString,
      accountId = accountId.toString,
      transactionId = transactionId.toString)
    
    // Since result is single account, we need only first list entry
    val r = process(req).extractOpt[KafkaInboundTransaction]
    r match {
      // Check does the response data match the requested data
      case Some(x) if transactionId.value != x.transactionId => Failure(ErrorMessages.InvalidGetTransactionConnectorResponse, Empty, Empty)
      case Some(x) if transactionId.value == x.transactionId => createNewTransaction(x)
      case _ => Failure(ErrorMessages.ConnectorEmptyResponse, Empty, Empty)
    }

  }
  
  messageDocs += MessageDocs(
    action = "obp.get.Transactions",
    kafkaConnectorVersion = formatVersion,
    description = "getTransactions from kafka",
    exampleRequestMessage = Extraction.decompose(GetTransactions(
      action = "obp.get.Transactions",
      version = formatVersion,
      userId = "",
      username = "",
      bankId = "",
      accountId = "",
      queryParams = "")),
    exampleResponseMessage = emptyObjectJson,
    errorResponseMessages = emptyObjectJson :: Nil
  )
  //TODO, this action is different from method name
  override def getTransactions(bankId: BankId, accountId: AccountId, queryParams: OBPQueryParam*): Box[List[Transaction]] = {
    val limit = queryParams.collect { case OBPLimit(value) => MaxRows[MappedTransaction](value) }.headOption
    val offset = queryParams.collect { case OBPOffset(value) => StartAt[MappedTransaction](value) }.headOption
    val fromDate = queryParams.collect { case OBPFromDate(date) => By_>=(MappedTransaction.tFinishDate, date) }.headOption
    val toDate = queryParams.collect { case OBPToDate(date) => By_<=(MappedTransaction.tFinishDate, date) }.headOption
    val ordering = queryParams.collect {
      //we don't care about the intended sort field and only sort on finish date for now
      case OBPOrdering(_, direction) =>
        direction match {
          case OBPAscending => OrderBy(MappedTransaction.tFinishDate, Ascending)
          case OBPDescending => OrderBy(MappedTransaction.tFinishDate, Descending)
        }
    }
    val optionalParams : Seq[QueryParam[MappedTransaction]] = Seq(limit.toSeq, offset.toSeq, fromDate.toSeq, toDate.toSeq, ordering.toSeq).flatten
    val mapperParams = Seq(By(MappedTransaction.bank, bankId.value), By(MappedTransaction.account, accountId.value)) ++ optionalParams
  
    val req = GetTransactions(
      action = "obp.get.Transactions",
      version = formatVersion,
      userId = AuthUser.getCurrentResourceUserUserId,
      username = AuthUser.getCurrentUserUsername,
      bankId = bankId.toString,
      accountId = accountId.toString,
      queryParams = queryParams.toString)
    
    implicit val formats = net.liftweb.json.DefaultFormats
    val rList = process(req).extract[List[KafkaInboundTransaction]]
    // Check does the response data match the requested data
    val isCorrect = rList.forall(x=>x.accountId == accountId.value && x.bankId == bankId.value)
    if (!isCorrect) throw new Exception(ErrorMessages.InvalidGetTransactionsConnectorResponse)
    // Populate fields and generate result
    val res = for {
      r <- rList
      transaction <- createNewTransaction(r)
    } yield {
      transaction
    }
    Full(res)
    //TODO is this needed updateAccountTransactions(bankId, accountId)
  }
  
  messageDocs += MessageDocs(
    action = "obp.get.Account",
    kafkaConnectorVersion = formatVersion,
    description = "getBankAccount from kafka",
    exampleRequestMessage = Extraction.decompose(GetBankAccount(
      action = "obp.get.Account",
      version = formatVersion,
      userId = "",
      username = "",
      bankId = "",
      accountId = "")),
    exampleResponseMessage = emptyObjectJson,
    errorResponseMessages = emptyObjectJson :: Nil
  )
  override def getBankAccount(bankId: BankId, accountId: AccountId): Box[KafkaBankAccount] = {
    // Generate random uuid to be used as request-response match id
    val req = GetBankAccount(
      action = "obp.get.Account",
      version = formatVersion,
      userId = AuthUser.getCurrentResourceUserUserId,
      username = AuthUser.getCurrentUserUsername,
      bankId = bankId.toString,
      accountId = accountId.value)
    
    // Since result is single account, we need only first list entry
    implicit val formats = net.liftweb.json.DefaultFormats
    val r = {
      cachedAccount.getOrElseUpdate( req.toString, () => process(req).extract[KafkaInboundAccount])
    }
    // Check does the response data match the requested data
    val accResp = List((BankId(r.bankId), AccountId(r.accountId))).toSet
    val acc = List((bankId, accountId)).toSet
    if ((accResp diff acc).size > 0) throw new Exception(ErrorMessages.InvalidGetBankAccountConnectorResponse)

    createMappedAccountDataIfNotExisting(r.bankId, r.accountId, r.label)

    Full(new KafkaBankAccount(r))
  }
  
  messageDocs += MessageDocs(
    action = "obp.get.Accounts",
    kafkaConnectorVersion = formatVersion,
    description = "getBankAccounts from kafka",
    exampleRequestMessage = Extraction.decompose(GetBankAccounts(
      action = "obp.get.Accounts",
      version = formatVersion,
      userId = "",
      username = "",
      bankId = "",
      accountId = "")),
    exampleResponseMessage = emptyObjectJson,
    errorResponseMessages = emptyObjectJson :: Nil
  )
  override def getBankAccounts(accts: List[(BankId, AccountId)]): List[KafkaBankAccount] = {
    val primaryUserIdentifier = AuthUser.getCurrentUserUsername

    val r:List[KafkaInboundAccount] = accts.flatMap { a => {

        logger.info (s"KafkaMappedConnnector.getBankAccounts with params ${a._1.value} and  ${a._2.value} and primaryUserIdentifier is $primaryUserIdentifier")

        val req = GetBankAccounts(
          action = "obp.get.Accounts",
          version = formatVersion,
          userId = AuthUser.getCurrentResourceUserUserId,
          username = AuthUser.getCurrentUserUsername,
          bankId = a._1.value,
          accountId = a._2.value)
      
        implicit val formats = net.liftweb.json.DefaultFormats
        val r = {
          cachedAccounts.getOrElseUpdate( req.toString, () => process(req).extract[List[KafkaInboundAccount]])
        }
        r
      }
    }

    // Check does the response data match the requested data
    val accRes = for(row <- r) yield {
      (BankId(row.bankId), AccountId(row.accountId))
    }
    if ((accRes.toSet diff accts.toSet).size > 0) throw new Exception(ErrorMessages.InvalidGetBankAccountsConnectorResponse)

    r.map { t =>
      createMappedAccountDataIfNotExisting(t.bankId, t.accountId, t.label)
      new KafkaBankAccount(t) }
  }
  
  //TODO the method name is different from action 
  messageDocs += MessageDocs(
    action = "obp.get.Account",
    kafkaConnectorVersion = formatVersion,
    description = "getAccountByNumber from kafka",
    exampleRequestMessage = Extraction.decompose(GetAccountByNumber(
      action = "obp.get.Account",
      version = formatVersion,
      userId = "",
      username = "",
      bankId = "",
      number = "")),
    exampleResponseMessage = emptyObjectJson,
    errorResponseMessages = emptyObjectJson :: Nil
  )
  
  private def getAccountByNumber(bankId : BankId, number : String) : Box[AccountType] = {
    // Generate random uuid to be used as request-respose match id
    val req = GetAccountByNumber(
      action = "obp.get.Account",
      version = formatVersion,
      userId = AuthUser.getCurrentResourceUserUserId,
      username = AuthUser.getCurrentUserUsername,
      bankId = bankId.toString,
      number = number
    )
    
    // Since result is single account, we need only first list entry
    implicit val formats = net.liftweb.json.DefaultFormats
    val r = {
      cachedAccount.getOrElseUpdate( req.toString, () => process(req).extract[KafkaInboundAccount])
    }
    createMappedAccountDataIfNotExisting(r.bankId, r.accountId, r.label)
    Full(new KafkaBankAccount(r))
  }

  def getCounterpartyFromTransaction(thisBankId : BankId, thisAccountId : AccountId, metadata : CounterpartyMetadata) : Box[Counterparty] = {
    //because we don't have a db backed model for OtherBankAccounts, we need to construct it from an
    //OtherBankAccountMetadata and a transaction
    val t = getTransactions(thisBankId, thisAccountId).map { t =>
      t.filter { e =>
        if (e.otherAccount.thisAccountId == metadata.getAccountNumber)
          true
        else
          false
      }
    }.get.head

    val res = new Counterparty(
      //counterparty id is defined to be the id of its metadata as we don't actually have an id for the counterparty itself
      counterPartyId = metadata.metadataId,
      label = metadata.getHolder,
      nationalIdentifier = t.otherAccount.nationalIdentifier,
      otherBankRoutingAddress = None,
      otherAccountRoutingAddress = t.otherAccount.otherAccountRoutingAddress,
      thisAccountId = AccountId(metadata.getAccountNumber),
      thisBankId = t.otherAccount.thisBankId,
      kind = t.otherAccount.kind,
      otherBankId = thisBankId,
      otherAccountId = thisAccountId,
      alreadyFoundMetadata = Some(metadata),
      name = "",
      otherBankRoutingScheme = "",
      otherAccountRoutingScheme="",
      otherAccountProvider = "",
      isBeneficiary = true
    )
    Full(res)
  }

  /**
   *
   * refreshes transactions via hbci if the transaction info is sourced from hbci
   *
   *  Checks if the last update of the account was made more than one hour ago.
   *  if it is the case we put a message in the message queue to ask for
   *  transactions updates
   *
   *  It will be used each time we fetch transactions from the DB. But the test
   *  is performed in a different thread.
   */
  /*
  private def updateAccountTransactions(bankId : BankId, accountId : AccountId) = {

    for {
      bank <- getBank(bankId)
      account <- getBankAccountType(bankId, accountId)
    } {
      spawn{
        val useMessageQueue = Props.getBool("messageQueue.updateBankAccountsTransaction", false)
        val outDatedTransactions = Box!!account.lastUpdate match {
          case Full(l) => now after time(l.getTime + hours(Props.getInt("messageQueue.updateTransactionsInterval", 1)))
          case _ => true
        }
        //if(outDatedTransactions && useMessageQueue) {
        //  UpdatesRequestSender.sendMsg(UpdateBankAccount(account.number, bank.national_identifier.get))
        //}
      }
    }
  }
  */


  // Get all counterparties related to an account
  override def getCounterpartiesFromTransaction(bankId: BankId, accountId: AccountId): List[Counterparty] =
    Counterparties.counterparties.vend.getMetadatas(bankId, accountId).flatMap(getCounterpartyFromTransaction(bankId, accountId, _))

  // Get one counterparty related to a bank account
  override def getCounterpartyFromTransaction(bankId: BankId, accountId: AccountId, counterpartyID: String): Box[Counterparty] =
    // Get the metadata and pass it to getOtherBankAccount to construct the other account.
    Counterparties.counterparties.vend.getMetadata(bankId, accountId, counterpartyID).flatMap(getCounterpartyFromTransaction(bankId, accountId, _))

  def getCounterparty(thisBankId: BankId, thisAccountId: AccountId, couterpartyId: String): Box[Counterparty] = {
    //note: kafka mode just used the mapper data
    LocalMappedConnector.getCounterparty(thisBankId, thisAccountId, couterpartyId)
  }

  messageDocs += MessageDocs(
    action = "obp.get.CounterpartyByCounterpartyId",
    kafkaConnectorVersion = formatVersion,
    description = "getCounterpartyByCounterpartyId from kafka ",
    Extraction.decompose(GetCounterpartyByCounterpartyId(
      action = "obp.get.CounterpartyByCounterpartyId",
      version = formatVersion,
      userId = "",
      username = "",
      counterpartyId = "")),
    emptyObjectJson,
    emptyObjectJson :: Nil
  )

  override def getCounterpartyByCounterpartyId(counterpartyId: CounterpartyId): Box[CounterpartyTrait] = {
    if (Props.getBool("get_counterparties_from_OBP_DB", true)) {
      MappedCounterparty.find(By(MappedCounterparty.mCounterPartyId, counterpartyId.value))
    } else {
      val req = GetCounterpartyByCounterpartyId(
        action = "obp.get.CounterpartyByCounterpartyId",
        version = formatVersion,
        userId = AuthUser.getCurrentResourceUserUserId,
        username = AuthUser.getCurrentUserUsername,
        counterpartyId = counterpartyId.toString
      )
      // Since result is single account, we need only first list entry
      implicit val formats = net.liftweb.json.DefaultFormats
      val r = {
        cachedCounterparty.getOrElseUpdate(req.toString, () => process(req).extract[KafkaInboundCounterparty])
      }
      Full(new KafkaCounterparty(r))
    }
  }
  
  messageDocs += MessageDocs(
    action = "obp.get.CounterpartyByIban",
    kafkaConnectorVersion = formatVersion,
    description = "getCounterpartyByIban from kafka ",
    exampleRequestMessage = Extraction.decompose(GetCounterpartyByIban(
      action = "obp.get.CounterpartyByIban",
      version = formatVersion,
      userId = "",
      username = "",
      otherAccountRoutingAddress = "",
      otherAccountRoutingScheme = "")),
    exampleResponseMessage = emptyObjectJson,
    errorResponseMessages = emptyObjectJson :: Nil
  )

  override def getCounterpartyByIban(iban: String): Box[CounterpartyTrait] = {
    if (Props.getBool("get_counterparties_from_OBP_DB", true)) {
      MappedCounterparty.find(
        By(MappedCounterparty.mOtherAccountRoutingAddress, iban),
        By(MappedCounterparty.mOtherAccountRoutingScheme, "IBAN")
      )
    } else {
      val req = GetCounterpartyByIban(
        action = "obp.get.CounterpartyByIban",
        version = formatVersion,
        userId = AuthUser.getCurrentResourceUserUserId,
        username = AuthUser.getCurrentUserUsername,
        otherAccountRoutingAddress = iban,
        otherAccountRoutingScheme = "IBAN"
      )

      val r = process(req).extract[KafkaInboundCounterparty]

      Full(new KafkaCounterparty(r))
    }
  }

  override def getCounterparties(thisBankId: BankId, thisAccountId: AccountId,viewId :ViewId): Box[List[CounterpartyTrait]] = {
    //note: kafka mode just used the mapper data
    LocalMappedConnector.getCounterparties(thisBankId, thisAccountId, viewId)
  }
  override def getPhysicalCards(user: User): List[PhysicalCard] =
    List()

  override def getPhysicalCardsForBank(bank: Bank, user: User): List[PhysicalCard] =
    List()

  def AddPhysicalCard(bankCardNumber: String,
                      nameOnCard: String,
                      issueNumber: String,
                      serialNumber: String,
                      validFrom: Date,
                      expires: Date,
                      enabled: Boolean,
                      cancelled: Boolean,
                      onHotList: Boolean,
                      technology: String,
                      networks: List[String],
                      allows: List[String],
                      accountId: String,
                      bankId: String,
                      replacement: Option[CardReplacementInfo],
                      pinResets: List[PinResetInfo],
                      collected: Option[CardCollectionInfo],
                      posted: Option[CardPostedInfo]
                     ) : Box[PhysicalCard] = {
    Empty
  }


  protected override def makePaymentImpl(fromAccount: KafkaBankAccount,
                                         toAccount: KafkaBankAccount,
                                         toCounterparty: CounterpartyTrait,
                                         amt: BigDecimal,
                                         description: String,
                                         transactionRequestType: TransactionRequestType,
                                         chargePolicy: String): Box[TransactionId] = {

    val sentTransactionId = saveTransaction(fromAccount,
                                            toAccount,
                                            toCounterparty,
                                            amt,
                                            description,
                                            transactionRequestType,
                                            chargePolicy)

    sentTransactionId
  }
  
  
  messageDocs += MessageDocs(
    action = "obp.put.Transaction",
    kafkaConnectorVersion = formatVersion,
    description = "saveTransaction from kafka",
    exampleRequestMessage = Extraction.decompose(SaveTransaction(
      action = "",
      version = "",
      userId = "",
      username = "",
  
      // fromAccount
      fromAccountName = "",
      fromAccountId = "",
      fromAccountBankId = "",
  
      // transaction details
      transactionId = "",
      transactionRequestType = "",
      transactionAmount = "",
      transactionCurrency = "",
      transactionChargePolicy = "",
      transactionChargeAmount = "",
      transactionChargeCurrency = "",
      transactionDescription = "",
      transactionPostedDate = "",
  
      // toAccount or toCounterparty
      toCounterpartyId = "",
      toCounterpartyName = "",
      toCounterpartyCurrency = "",
      toCounterpartyRoutingAddress = "",
      toCounterpartyRoutingScheme = "",
      toCounterpartyBankRoutingAddress = "",
      toCounterpartyBankRoutingScheme  = "")),
    exampleResponseMessage = emptyObjectJson,
    errorResponseMessages = emptyObjectJson :: Nil
  )
  
  /**
   * Saves a transaction with amount @amount and counterparty @counterparty for account @account. Returns the id
   * of the saved transaction.
   */
  private def saveTransaction(fromAccount: KafkaBankAccount,
                              toAccount: KafkaBankAccount,
                              toCounterparty: CounterpartyTrait,
                              amount: BigDecimal,
                              description: String,
                              transactionRequestType: TransactionRequestType,
                              chargePolicy: String): Box[TransactionId] = {
  
    val postedDate = ZonedDateTime.now.toString
    val transactionId = UUID.randomUUID().toString
  
    val req =
      if (toAccount != null && toCounterparty == null) {
        SaveTransaction(
          action = "obp.put.Transaction",
          version = formatVersion,
          userId = AuthUser.getCurrentResourceUserUserId,
          username = AuthUser.getCurrentUserUsername,
        
          // fromAccount
          fromAccountName = fromAccount.name,
          fromAccountId = fromAccount.accountId.value,
          fromAccountBankId = fromAccount.bankId.value,
        
          // transaction details
          transactionId = transactionId,
          transactionRequestType = transactionRequestType.value,
          transactionAmount = amount.bigDecimal.toString,
          transactionCurrency = fromAccount.currency,
          transactionChargePolicy = chargePolicy,
          transactionChargeAmount = "0.0", // TODO get correct charge amount
          transactionChargeCurrency = fromAccount.currency, // TODO get correct charge currency 
          transactionDescription = description,
          transactionPostedDate = postedDate,
        
          // toAccount or toCounterparty
          toCounterpartyId = toAccount.accountId.value,
          toCounterpartyName = toAccount.name,
          toCounterpartyCurrency = toAccount.currency,
          toCounterpartyRoutingAddress = toAccount.accountId.value,
          toCounterpartyRoutingScheme = "OBP",
          toCounterpartyBankRoutingAddress = toAccount.bankId.value,
          toCounterpartyBankRoutingScheme = "OBP")
      } else {
        SaveTransaction(
          action = "obp.put.Transaction",
          version = formatVersion,
          userId = AuthUser.getCurrentResourceUserUserId,
          username = AuthUser.getCurrentUserUsername,
        
          // fromAccount
          fromAccountName = fromAccount.name,
          fromAccountId = fromAccount.accountId.value,
          fromAccountBankId = fromAccount.bankId.value,
        
          // transaction details
          transactionId = transactionId,
          transactionRequestType = transactionRequestType.value,
          transactionAmount = amount.bigDecimal.toString,
          transactionCurrency = fromAccount.currency,
          transactionChargePolicy = chargePolicy,
          transactionChargeAmount = "0.0", // TODO get correct charge amount
          transactionChargeCurrency = fromAccount.currency, // TODO get correct charge currency 
          transactionDescription = description,
          transactionPostedDate = postedDate,
        
          // toAccount or toCounterparty
          toCounterpartyId = toCounterparty.counterpartyId,
          toCounterpartyName = toCounterparty.name,
          toCounterpartyCurrency = fromAccount.currency, // TODO toCounterparty.currency
          toCounterpartyRoutingAddress = toCounterparty.otherAccountRoutingAddress,
          toCounterpartyRoutingScheme = toCounterparty.otherAccountRoutingScheme,
          toCounterpartyBankRoutingAddress = toCounterparty.otherBankRoutingAddress,
          toCounterpartyBankRoutingScheme = toCounterparty.otherBankRoutingScheme)
      } 

    if ( toAccount == null && toCounterparty == null ) {
        logger.error(s"error calling saveTransaction: toAccount=${toAccount} toCounterparty=${toCounterparty}")
        return Empty
    }

    // Since result is single account, we need only first list entry
    val r = process(req)

    r.extract[KafkaInboundTransactionId] match {
      case r: KafkaInboundTransactionId => Full(TransactionId(r.transactionId))
      case _ => Empty 
    }

  }
  messageDocs += MessageDocs(
    action = "obp.get.TransactionRequestStatusesImpl",
    kafkaConnectorVersion = formatVersion,
    description = "getTransactionRequestStatusesImpl from kafka",
    exampleRequestMessage = Extraction.decompose(GetTransactionRequestStatusesImpl(
      action = "obp.get.TransactionRequestStatusesImpl",
      version = formatVersion
      )),
    exampleResponseMessage = emptyObjectJson,
    errorResponseMessages = emptyObjectJson :: Nil
  )
  /*
    Transaction Requests
  */
  override def getTransactionRequestStatusesImpl() : Box[TransactionRequestStatus] = {
    logger.info(s"tKafka getTransactionRequestStatusesImpl sart: ")
    val req = GetTransactionRequestStatusesImpl(
      action = "obp.get.TransactionRequestStatusesImpl",
      version = formatVersion
    )
    //TODO need more clear error handling to user, if it is Empty or Error now,all response Empty. 
    val r = try{
      val response = process(req).extract[KafkaInboundTransactionRequestStatus]
      Full(new KafkaTransactionRequestStatus(response))
    }catch {
      case _ => Empty
    }
    
    logger.info(s"Kafka getTransactionRequestStatusesImpl response: ${r.toString}") 
    r
  }

  override def createTransactionRequestImpl(transactionRequestId: TransactionRequestId, transactionRequestType: TransactionRequestType,
                                            account : BankAccount, counterparty : BankAccount, body: TransactionRequestBody,
                                            status: String, charge: TransactionRequestCharge) : Box[TransactionRequest] = {
    val mappedTransactionRequest = MappedTransactionRequest.create
      .mTransactionRequestId(transactionRequestId.value)
      .mType(transactionRequestType.value)
      .mFrom_BankId(account.bankId.value)
      .mFrom_AccountId(account.accountId.value)
      .mTo_BankId(counterparty.bankId.value)
      .mTo_AccountId(counterparty.accountId.value)
      .mBody_Value_Currency(body.value.currency)
      .mBody_Value_Amount(body.value.amount)
      .mBody_Description(body.description)
      .mStatus(status)
      .mStartDate(now)
      .mEndDate(now).saveMe
    Full(mappedTransactionRequest).flatMap(_.toTransactionRequest)
  }


  //Note: now call the local mapper to store data
  protected override def createTransactionRequestImpl210(transactionRequestId: TransactionRequestId,
                                                         transactionRequestType: TransactionRequestType,
                                                         fromAccount: BankAccount,
                                                         toAccount: BankAccount,
                                                         toCounterparty: CounterpartyTrait,
                                                         transactionRequestCommonBody: TransactionRequestCommonBodyJSON,
                                                         details: String, status: String,
                                                         charge: TransactionRequestCharge,
                                                         chargePolicy: String): Box[TransactionRequest] = {

    LocalMappedConnector.createTransactionRequestImpl210(transactionRequestId: TransactionRequestId,
                                                         transactionRequestType: TransactionRequestType,
                                                         fromAccount: BankAccount, toAccount: BankAccount,
                                                         toCounterparty: CounterpartyTrait,
                                                         transactionRequestCommonBody: TransactionRequestCommonBodyJSON,
                                                         details: String,
                                                         status: String,
                                                         charge: TransactionRequestCharge,
                                                         chargePolicy: String)
  }
  //Note: now call the local mapper to store data
  override def saveTransactionRequestTransactionImpl(transactionRequestId: TransactionRequestId, transactionId: TransactionId): Box[Boolean] = {
    LocalMappedConnector.saveTransactionRequestTransactionImpl(transactionRequestId: TransactionRequestId, transactionId: TransactionId)
  }

  override def saveTransactionRequestChallengeImpl(transactionRequestId: TransactionRequestId, challenge: TransactionRequestChallenge): Box[Boolean] = {
    val mappedTransactionRequest = MappedTransactionRequest.find(By(MappedTransactionRequest.mTransactionRequestId, transactionRequestId.value))
    mappedTransactionRequest match {
      case Full(tr: MappedTransactionRequest) => Full{
        tr.mChallenge_Id(challenge.id)
        tr.mChallenge_AllowedAttempts(challenge.allowed_attempts)
        tr.mChallenge_ChallengeType(challenge.challenge_type).save
      }
      case _ => Failure(s"Couldn't find transaction request ${transactionRequestId} to set transactionId")
    }
  }

  override def saveTransactionRequestStatusImpl(transactionRequestId: TransactionRequestId, status: String): Box[Boolean] = {
    val mappedTransactionRequest = MappedTransactionRequest.find(By(MappedTransactionRequest.mTransactionRequestId, transactionRequestId.value))
    mappedTransactionRequest match {
      case Full(tr: MappedTransactionRequest) => Full(tr.mStatus(status).save)
      case _ => Failure(s"Couldn't find transaction request ${transactionRequestId} to set status")
    }
  }


  override def getTransactionRequestsImpl(fromAccount : BankAccount) : Box[List[TransactionRequest]] = {
    val transactionRequests = MappedTransactionRequest.findAll(By(MappedTransactionRequest.mFrom_AccountId, fromAccount.accountId.value),
                                                               By(MappedTransactionRequest.mFrom_BankId, fromAccount.bankId.value))

    Full(transactionRequests.flatMap(_.toTransactionRequest))
  }

  override def getTransactionRequestsImpl210(fromAccount : BankAccount) : Box[List[TransactionRequest]] = {
    val transactionRequests = MappedTransactionRequest.findAll(By(MappedTransactionRequest.mFrom_AccountId, fromAccount.accountId.value),
      By(MappedTransactionRequest.mFrom_BankId, fromAccount.bankId.value))

    Full(transactionRequests.flatMap(_.toTransactionRequest))
  }

  override def getTransactionRequestImpl(transactionRequestId: TransactionRequestId): Box[TransactionRequest] = {
    val transactionRequest = MappedTransactionRequest.find(By(MappedTransactionRequest.mTransactionRequestId, transactionRequestId.value))
    transactionRequest.flatMap(_.toTransactionRequest)
  }


  override def getTransactionRequestTypesImpl(fromAccount: BankAccount): Box[List[TransactionRequestType]] = {
    val validTransactionRequestTypes = Props.get("transactionRequests_supported_types", "").split(",").map(x => TransactionRequestType(x)).toList
    Full(validTransactionRequestTypes)
  }

  /*
    Bank account creation
   */

  //creates a bank account (if it doesn't exist) and creates a bank (if it doesn't exist)
  //again assume national identifier is unique
  override def createBankAndAccount(bankName: String, bankNationalIdentifier: String, accountNumber: String,
                                    accountType: String, accountLabel: String,  currency: String, accountHolderName: String): (Bank, BankAccount) = {
    //don't require and exact match on the name, just the identifier
    val bank: Bank = MappedBank.find(By(MappedBank.national_identifier, bankNationalIdentifier)) match {
      case Full(b) =>
        logger.info(s"bank with id ${b.bankId} and national identifier ${b.nationalIdentifier} found")
        b
      case _ =>
        logger.info(s"creating bank with national identifier $bankNationalIdentifier")
        //TODO: need to handle the case where generatePermalink returns a permalink that is already used for another bank
        MappedBank.create
          .permalink(Helper.generatePermalink(bankName))
          .fullBankName(bankName)
          .shortBankName(bankName)
          .national_identifier(bankNationalIdentifier)
          .saveMe()
    }

    //TODO: pass in currency as a parameter?
    val account = createAccountIfNotExisting(
      bank.bankId,
      AccountId(UUID.randomUUID().toString),
      accountNumber,
      accountType,
      accountLabel,
      currency,
      0L,
      accountHolderName
    )

    (bank, account)
  }

  //for sandbox use -> allows us to check if we can generate a new test account with the given number
  override def accountExists(bankId: BankId, accountNumber: String): Boolean = {
    getAccountByNumber(bankId, accountNumber) != null
  }

  //remove an account and associated transactions
  override def removeAccount(bankId: BankId, accountId: AccountId) : Boolean = {
    //delete comments on transactions of this account
    val commentsDeleted = Comments.comments.vend.bulkDeleteComments(bankId, accountId)

    //delete narratives on transactions of this account
    val narrativesDeleted = MappedNarrative.bulkDelete_!!(
      By(MappedNarrative.bank, bankId.value),
      By(MappedNarrative.account, accountId.value)
    )

    //delete narratives on transactions of this account
    val tagsDeleted = Tags.tags.vend.bulkDeleteTags(bankId, accountId)

    //delete WhereTags on transactions of this account
    val whereTagsDeleted = WhereTags.whereTags.vend.bulkDeleteWhereTags(bankId, accountId)

    //delete transaction images on transactions of this account
    val transactionImagesDeleted = TransactionImages.transactionImages.vend.bulkDeleteTransactionImage(bankId, accountId)

    //delete transactions of account
    val transactionsDeleted = MappedTransaction.bulkDelete_!!(
      By(MappedTransaction.bank, bankId.value),
      By(MappedTransaction.account, accountId.value)
    )

    //remove view privileges
    val privilegesDeleted = Views.views.vend.removeAllPermissions(bankId, accountId)

    //delete views of account
    val viewsDeleted = Views.views.vend.removeAllViews(bankId, accountId)

    //delete account
    val account = getBankAccount(bankId, accountId)

    val accountDeleted = account match {
      case acc => true //acc.delete_! //TODO
      case _ => false
    }

    commentsDeleted && narrativesDeleted && tagsDeleted && whereTagsDeleted && transactionImagesDeleted &&
      transactionsDeleted && privilegesDeleted && viewsDeleted && accountDeleted
}

  //creates a bank account for an existing bank, with the appropriate values set. Can fail if the bank doesn't exist
  override def createSandboxBankAccount(bankId: BankId, accountId: AccountId, accountNumber: String,
                                        accountType: String, accountLabel: String, currency: String,
                                        initialBalance: BigDecimal, accountHolderName: String): Box[BankAccount] = {

    for {
      bank <- getBank(bankId) //bank is not really used, but doing this will ensure account creations fails if the bank doesn't
    } yield {

      val balanceInSmallestCurrencyUnits = Helper.convertToSmallestCurrencyUnits(initialBalance, currency)
      createAccountIfNotExisting(bankId, accountId, accountNumber, accountType, accountLabel, currency, balanceInSmallestCurrencyUnits, accountHolderName)
    }

  }

  //sets a user as an account owner/holder
  override def setAccountHolder(bankAccountUID: BankAccountUID, user: User): Unit = {
    AccountHolders.accountHolders.vend.createAccountHolder(user.resourceUserId.value, bankAccountUID.accountId.value, bankAccountUID.bankId.value)
  }

  private def createAccountIfNotExisting(bankId: BankId, accountId: AccountId, accountNumber: String,
                                         accountType: String, accountLabel: String, currency: String,
                                         balanceInSmallestCurrencyUnits: Long, accountHolderName: String) : BankAccount = {
    getBankAccount(bankId, accountId) match {
      case Full(a) =>
        logger.info(s"account with id $accountId at bank with id $bankId already exists. No need to create a new one.")
        a
      case _ => null //TODO
        /*
       new  KafkaBankAccount
          .bank(bankId.value)
          .theAccountId(accountId.value)
          .accountNumber(accountNumber)
          .accountType(accountType)
          .accountLabel(accountLabel)
          .accountCurrency(currency)
          .accountBalance(balanceInSmallestCurrencyUnits)
          .holder(accountHolderName)
          .saveMe()
          */
    }
  }

  private def createMappedAccountDataIfNotExisting(bankId: String, accountId: String, label: String) : Boolean = {
    MappedBankAccountData.find(By(MappedBankAccountData.accountId, accountId),
                                    By(MappedBankAccountData.bankId, bankId)) match {
      case Empty =>
        val data = new MappedBankAccountData
        data.setAccountId(accountId)
        data.setBankId(bankId)
        data.setLabel(label)
        data.save()
        true
      case _ =>
        logger.info(s"account data with id $accountId at bank with id $bankId already exists. No need to create a new one.")
        false
    }
  }

  /*
    End of bank account creation
   */


  /*
    Transaction importer api
   */

  //used by the transaction import api
  override def updateAccountBalance(bankId: BankId, accountId: AccountId, newBalance: BigDecimal): Boolean = {

    //this will be Full(true) if everything went well
    val result = for {
      acc <- getBankAccount(bankId, accountId)
      bank <- getBank(bankId)
    } yield {
      //acc.balance = newBalance
      setBankAccountLastUpdated(bank.nationalIdentifier, acc.number, now)
    }

    result.getOrElse(false)
  }

  //transaction import api uses bank national identifiers to uniquely indentify banks,
  //which is unfortunate as theoretically the national identifier is unique to a bank within
  //one country
  private def getBankByNationalIdentifier(nationalIdentifier : String) : Box[Bank] = {
    MappedBank.find(By(MappedBank.national_identifier, nationalIdentifier))
  }


  private val bigDecimalFailureHandler : PartialFunction[Throwable, Unit] = {
    case ex : NumberFormatException => {
      logger.warn(s"could not convert amount to a BigDecimal: $ex")
    }
  }

  //used by transaction import api call to check for duplicates
  override def getMatchingTransactionCount(bankNationalIdentifier : String, accountNumber : String, amount: String, completed: Date, otherAccountHolder: String): Int = {
    //we need to convert from the legacy bankNationalIdentifier to BankId, and from the legacy accountNumber to AccountId
    val count = for {
      bankId <- getBankByNationalIdentifier(bankNationalIdentifier).map(_.bankId)
      account <- getAccountByNumber(bankId, accountNumber)
      amountAsBigDecimal <- tryo(bigDecimalFailureHandler)(BigDecimal(amount))
    } yield {

      val amountInSmallestCurrencyUnits =
        Helper.convertToSmallestCurrencyUnits(amountAsBigDecimal, account.currency)

      MappedTransaction.count(
        By(MappedTransaction.bank, bankId.value),
        By(MappedTransaction.account, account.accountId.value),
        By(MappedTransaction.amount, amountInSmallestCurrencyUnits),
        By(MappedTransaction.tFinishDate, completed),
        By(MappedTransaction.counterpartyAccountHolder, otherAccountHolder))
    }

    //icky
    count.map(_.toInt) getOrElse 0
  }

  //used by transaction import api
  override def createImportedTransaction(transaction: ImporterTransaction): Box[Transaction] = {
    //we need to convert from the legacy bankNationalIdentifier to BankId, and from the legacy accountNumber to AccountId
    val obpTransaction = transaction.obp_transaction
    val thisAccount = obpTransaction.this_account
    val nationalIdentifier = thisAccount.bank.national_identifier
    val accountNumber = thisAccount.number
    for {
      bank <- getBankByNationalIdentifier(transaction.obp_transaction.this_account.bank.national_identifier) ?~!
        s"No bank found with national identifier $nationalIdentifier"
      bankId = bank.bankId
      account <- getAccountByNumber(bankId, accountNumber)
      details = obpTransaction.details
      amountAsBigDecimal <- tryo(bigDecimalFailureHandler)(BigDecimal(details.value.amount))
      newBalanceAsBigDecimal <- tryo(bigDecimalFailureHandler)(BigDecimal(details.new_balance.amount))
      amountInSmallestCurrencyUnits = Helper.convertToSmallestCurrencyUnits(amountAsBigDecimal, account.currency)
      newBalanceInSmallestCurrencyUnits = Helper.convertToSmallestCurrencyUnits(newBalanceAsBigDecimal, account.currency)
      otherAccount = obpTransaction.other_account
      mappedTransaction = MappedTransaction.create
        .bank(bankId.value)
        .account(account.accountId.value)
        .transactionType(details.kind)
        .amount(amountInSmallestCurrencyUnits)
        .newAccountBalance(newBalanceInSmallestCurrencyUnits)
        .currency(account.currency)
        .tStartDate(details.posted.`$dt`)
        .tFinishDate(details.completed.`$dt`)
        .description(details.label)
        .counterpartyAccountNumber(otherAccount.number)
        .counterpartyAccountHolder(otherAccount.holder)
        .counterpartyAccountKind(otherAccount.kind)
        .counterpartyNationalId(otherAccount.bank.national_identifier)
        .counterpartyBankName(otherAccount.bank.name)
        .counterpartyIban(otherAccount.bank.IBAN)
        .saveMe()
      transaction <- mappedTransaction.toTransaction(account)
    } yield transaction
  }

  override def setBankAccountLastUpdated(bankNationalIdentifier: String, accountNumber : String, updateDate: Date) : Boolean = {
    val result = for {
      bankId <- getBankByNationalIdentifier(bankNationalIdentifier).map(_.bankId)
      account <- getAccountByNumber(bankId, accountNumber)
    } yield {
        val acc = getBankAccount(bankId, account.accountId)
        acc match {
          case a => true //a.lastUpdate = updateDate //TODO
          case _ => logger.warn("can't set bank account.lastUpdated because the account was not found"); false
        }
    }
    result.getOrElse(false)
  }

  /*
    End of transaction importer api
   */


  override def updateAccountLabel(bankId: BankId, accountId: AccountId, label: String): Boolean = {
    //this will be Full(true) if everything went well
    val result = for {
      acc <- getBankAccount(bankId, accountId)
      bank <- getBank(bankId)
      d <- MappedBankAccountData.find(By(MappedBankAccountData.accountId, accountId.value), By(MappedBankAccountData.bankId, bank.bankId.value))
    } yield {
      d.setLabel(label)
      d.save()
    }
    result.getOrElse(false)
  }


  override def getProducts(bankId: BankId): Box[List[Product]] = Empty

  override def getProduct(bankId: BankId, productCode: ProductCode): Box[Product] = Empty

  override  def createOrUpdateBranch(branch: BranchJsonPost ): Box[Branch] = Empty

  override def getBranch(bankId : BankId, branchId: BranchId) : Box[MappedBranch]= Empty

  override def getConsumerByConsumerId(consumerId: Long): Box[Consumer] = Empty
  
  messageDocs += MessageDocs(
    action = "obp.get.CurrentFxRate",
    kafkaConnectorVersion = formatVersion,
    description = "getCurrentFxRate from kafka",
    exampleRequestMessage = Extraction.decompose(GetCurrentFxRate(
      action = "obp.get.CurrentFxRate",
      version = formatVersion,
      userId = "",
      username = "",
      fromCurrencyCode = "",
      toCurrencyCode = "")),
    exampleResponseMessage = emptyObjectJson,
    errorResponseMessages = emptyObjectJson :: Nil
  )
  // get the latest FXRate specified by fromCurrencyCode and toCurrencyCode.
  override def getCurrentFxRate(fromCurrencyCode: String, toCurrencyCode: String): Box[FXRate] = {
    // Create request argument list
    val req = GetCurrentFxRate(
      action = "obp.get.CurrentFxRate",
      version = formatVersion,
      userId = AuthUser.getCurrentResourceUserUserId,
      username = AuthUser.getCurrentUserUsername,
      fromCurrencyCode = fromCurrencyCode,
      toCurrencyCode = toCurrencyCode)
    
    val r = {
      cachedFxRate.getOrElseUpdate(req.toString, () => process(req).extract[KafkaInboundFXRate])
    }
    // Return result
    Full(new KafkaFXRate(r))
  }
  
  messageDocs += MessageDocs(
    action = "obp.get.TransactionRequestTypeCharge",
    kafkaConnectorVersion = formatVersion,
    description = "getTransactionRequestTypeCharge from kafka",
    exampleRequestMessage = Extraction.decompose(GetTransactionRequestTypeCharge(
      action = "obp.get.TransactionRequestTypeCharge",
      version = formatVersion,
      userId = "",
      username = "",
      bankId = "",
      accountId = "",
      viewId = "",
      transactionRequestType = "")),
    exampleResponseMessage = emptyObjectJson,
    errorResponseMessages = emptyObjectJson :: Nil
  )
  //get the current charge specified by bankId, accountId, viewId and transactionRequestType
  override def getTransactionRequestTypeCharge(bankId: BankId, accountId: AccountId, viewId: ViewId, transactionRequestType: TransactionRequestType): Box[TransactionRequestTypeCharge] = {

    // Create request argument list
    val req = GetTransactionRequestTypeCharge(
      action = "obp.get.TransactionRequestTypeCharge",
      version = formatVersion,
      userId = AuthUser.getCurrentResourceUserUserId,
      username = AuthUser.getCurrentUserUsername,
      bankId = bankId.value,
      accountId = accountId.value,
      viewId = viewId.value,
      transactionRequestType = transactionRequestType.value)
    
    // send the request to kafka and get response
    // TODO the error handling is not good enough, it should divide the error, empty and no-response.
    val r =  tryo {
        Full(cachedTransactionRequestTypeCharge.getOrElseUpdate(req.toString, () => process(req).extract[KafkaInboundTransactionRequestTypeCharge]))
      }

    // Return result
     val result = r match {
      case Full(f) =>  KafkaTransactionRequestTypeCharge(f.get)
      case _ =>
        val fromAccountCurrency: String = getBankAccount(bankId, accountId).get.currency
        KafkaTransactionRequestTypeCharge(KafkaInboundTransactionRequestTypeCharge(transactionRequestType.value, bankId.value, fromAccountCurrency, "0.00", "Warning! Default value!"))
      }

    // result
    Full(result)
  }

  override def getTransactionRequestTypeCharges(bankId: BankId, accountId: AccountId, viewId: ViewId, transactionRequestTypes: List[TransactionRequestType]): Box[List[TransactionRequestTypeCharge]] = {
    Full(transactionRequestTypes.map(getTransactionRequestTypeCharge(bankId, accountId, viewId,_).get))
  }

  override def getEmptyBankAccount(): Box[AccountType] = {
    Full(new KafkaBankAccount(KafkaInboundAccount(accountId = "",
                                                  bankId = "",
                                                  label = "",
                                                  number = "",
                                                  `type` = "",
                                                  balanceAmount = "",
                                                  balanceCurrency = "",
                                                  iban = "",
                                                  owners = Nil,
                                                  generate_public_view = true,
                                                  generate_accountants_view = true,
                                                  generate_auditors_view = true)))
  }

  /////////////////////////////////////////////////////////////////////////////

  // Helper for creating a transaction
  def createNewTransaction(r: KafkaInboundTransaction):Box[Transaction] = {
    var datePosted: Date = null
    if (r.postedDate != null) // && r.details.posted.matches("^[0-9]{8}$"))
      datePosted = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ENGLISH).parse(r.postedDate)

    var dateCompleted: Date = null
    if (r.completedDate != null) // && r.details.completed.matches("^[0-9]{8}$"))
      dateCompleted = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ENGLISH).parse(r.completedDate)

    for {
        counterpartyId <- tryo{r.counterpartyId}
        counterpartyName <- tryo{r.counterpartyName}
        thisAccount <- getBankAccount(BankId(r.bankId), AccountId(r.accountId))
        //creates a dummy OtherBankAccount without an OtherBankAccountMetadata, which results in one being generated (in OtherBankAccount init)
        dummyOtherBankAccount <- tryo{createCounterparty(counterpartyId, counterpartyName, thisAccount, None)}
        //and create the proper OtherBankAccount with the correct "id" attribute set to the metadataId of the OtherBankAccountMetadata object
        //note: as we are passing in the OtherBankAccountMetadata we don't incur another db call to get it in OtherBankAccount init
        counterparty <- tryo{createCounterparty(counterpartyId, counterpartyName, thisAccount, Some(dummyOtherBankAccount.metadata))}
      } yield {
        // Create new transaction
        new Transaction(
                         r.transactionId, // uuid:String
                         TransactionId(r.transactionId), // id:TransactionId
                         thisAccount, // thisAccount:BankAccount
                         counterparty, // otherAccount:OtherBankAccount
                         r.`type`, // transactionType:String
                         BigDecimal(r.amount), // val amount:BigDecimal
                         thisAccount.currency, // currency:String
                         Some(r.description), // description:Option[String]
                         datePosted, // startDate:Date
                         dateCompleted, // finishDate:Date
                         BigDecimal(r.newBalanceAmount) // balance:BigDecimal)
        )
    }
  }



  // Helper for creating other bank account
  def createCounterparty(counterpartyId: String, counterpartyName: String, o: KafkaBankAccount, alreadyFoundMetadata : Option[CounterpartyMetadata]) = {
    new Counterparty(
      counterPartyId = alreadyFoundMetadata.map(_.metadataId).getOrElse(""),
      label = counterpartyName,
      nationalIdentifier = "",
      otherBankRoutingAddress = None,
      otherAccountRoutingAddress = None,
      thisAccountId = AccountId(counterpartyId),
      thisBankId = BankId(""),
      kind = "",
      otherBankId = o.bankId,
      otherAccountId = o.accountId,
      alreadyFoundMetadata = alreadyFoundMetadata,
      name = "",
      otherBankRoutingScheme = "",
      otherAccountRoutingScheme="",
      otherAccountProvider = "",
      isBeneficiary = true
    )
  }

  
  def process(request: scala.Product): json.JValue = {
    val reqId = UUID.randomUUID().toString
    val requestToMap= stransferCaseClassToMap(request)
    if (producer.send(reqId, requestToMap, "1")) {
      // Request sent, now we wait for response with the same reqId
      val res = consumer.getResponse(reqId)
      return res
    }
    return json.parse("""{"error":"could not send message to kafka"}""")
  }
  
  /**
    * Have this function just to keep compatibility for KafkaMappedConnector_vMar2017 and  KafkaMappedConnector.scala
    * In KafkaMappedConnector.scala, we use Map[String, String]. Now we change to case class
    * eg: case class Company(name: String, address: String) -->
    * Company("TESOBE","Berlin")
    * Map(name->"TESOBE", address->"2")
    *
    * @param caseClassObject
    * @return Map[String, String]
    */
  def stransferCaseClassToMap(caseClassObject: scala.Product) = caseClassObject.getClass.getDeclaredFields.map(_.getName) // all field names
    .zip(caseClassObject.productIterator.to).toMap.asInstanceOf[Map[String, String]] // zipped with all values

}
